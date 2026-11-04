package com.vidacruz.triage.infrastructure.drivenadapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.application.result.ResultadoPublicacion;
import com.vidacruz.triage.support.AbstractProcesosTest;
import com.vidacruz.triage.support.ConsumidorDePrueba;
import com.vidacruz.triage.support.KafkaDePrueba;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Outbox -> Kafka con un broker Testcontainers PROPIO de esta clase (se puede pausar para simular que Kafka se cae).
 * El publicador se dispara explicitamente con el caso de uso; el proceso de fondo esta apagado en el perfil de test.
 */
class OutboxKafkaTests extends AbstractProcesosTest {

	static final KafkaDePrueba KAFKA = new KafkaDePrueba();

	@DynamicPropertySource
	static void kafkaPropio(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
	}

	@Autowired
	PublicarEventosPendientesUseCase publicar;

	@Autowired
	MeterRegistry meterRegistry;

	private boolean publicado(UUID id, String tipo) {
		return outbox(id).stream().anyMatch(e -> e.get("eventType").asText().equals(tipo) && e.get("_publicado").asBoolean());
	}

	/** Dispara ciclos hasta que se cumpla la condicion (hay un evento por paciente por ciclo y otros tests dejan pendientes). */
	private void publicarHasta(java.util.function.BooleanSupplier condicion, Duration plazo) {
		await().atMost(plazo).pollInterval(Duration.ofMillis(300)).until(() -> {
			publicar.publicarPendientes().block(Duration.ofSeconds(60));
			return condicion.getAsBoolean();
		});
	}

	@Test
	void losEventosLleganAlTopicConKeyHeadersYQuedanMarcadosComoPublicados() throws Exception {
		double publicadosAntes = meterRegistry.counter("triage.outbox.publicados").count();
		UUID id = registrar(cuerpoNormal(), "corr-outbox-1");
		cambiarEstado(id, "EN_CONSULTA").expectStatus().isOk();

		publicarHasta(() -> publicado(id, "paciente_registrado") && publicado(id, "paciente_en_consulta"), Duration.ofSeconds(60));

		try (var consumidor = new ConsumidorDePrueba(KAFKA.bootstrapServers(), "triaje.eventos")) {
			List<ConsumerRecord<String, String>> registros = consumidor.leerHasta(Duration.ofSeconds(30),
					leidos -> leidos.stream().filter(r -> id.toString().equals(r.key())).count() >= 2)
					.stream().filter(r -> id.toString().equals(r.key())).toList();

			assertThat(registros).hasSize(2);
			// orden por paciente: registrado antes que en_consulta
			assertThat(registros).extracting(r -> ConsumidorDePrueba.cabecera(r, "eventType"))
					.containsExactly("paciente_registrado", "paciente_en_consulta");
			assertThat(ConsumidorDePrueba.cabecera(registros.get(0), "correlationId")).isEqualTo("corr-outbox-1");
			for (ConsumerRecord<String, String> r : registros) {
				JsonNode envelope = objectMapper.readTree(r.value());
				assertThat(r.key()).isEqualTo(id.toString());                                     // key = pacienteId
				assertThat(ConsumidorDePrueba.cabecera(r, "eventId")).isEqualTo(envelope.get("eventId").asText());
				assertThat(ConsumidorDePrueba.cabecera(r, "eventType")).isEqualTo(envelope.get("eventType").asText());
				assertThat(ConsumidorDePrueba.cabecera(r, "correlationId")).isEqualTo(envelope.get("correlationId").asText());
				assertThat(envelope.get("payload").get("pacienteId").asText()).isEqualTo(id.toString());
			}
		}
		assertThat(outbox(id)).allSatisfy(e -> assertThat(e.get("_publicado").asBoolean()).isTrue());
		assertThat(meterRegistry.counter("triage.outbox.publicados").count()).isGreaterThanOrEqualTo(publicadosAntes + 2);
	}

	@Test
	void conKafkaCaidoElRegistroSigueFuncionandoLosEventosQuedanPendientesYSePublicanAlVolver() throws Exception {
		double fallosAntes = meterRegistry.counter("triage.outbox.fallos").count();
		KAFKA.caer();
		UUID id;
		try {
			id = registrar(cuerpoNormal());                                   // el registro NO depende de Kafka
			ResultadoPublicacion r = publicar.publicarPendientes().block(Duration.ofSeconds(60));

			assertThat(r.fallidos()).isGreaterThanOrEqualTo(1);
			JsonNode fila = outbox(id).get(0);
			assertThat(fila.get("_publicado").asBoolean()).isFalse();            // sigue pendiente
			assertThat(intentos(id)).isEqualTo(1);
			assertThat(ultimoError(id)).isNotBlank();
			assertThat(meterRegistry.counter("triage.outbox.fallos").count()).isGreaterThan(fallosAntes);
			assertThat(meterRegistry.get("triage.outbox.pendientes").gauge().value()).isGreaterThanOrEqualTo(1);
		}
		finally {
			KAFKA.volver();
		}

		publicarHasta(() -> publicado(id, "paciente_registrado"), Duration.ofSeconds(90));

		try (var consumidor = new ConsumidorDePrueba(KAFKA.bootstrapServers(), "triaje.eventos")) {
			var registros = consumidor.leerHasta(Duration.ofSeconds(30),
					leidos -> leidos.stream().anyMatch(rr -> id.toString().equals(rr.key())));
			assertThat(registros).anyMatch(rr -> id.toString().equals(rr.key()));
		}
	}

	private int intentos(UUID id) {
		return db.sql("SELECT intentos FROM outbox_eventos WHERE aggregate_id = :id ORDER BY created_at LIMIT 1")
				.bind("id", id).map(f -> f.get("intentos", Integer.class)).one().block();
	}

	private String ultimoError(UUID id) {
		return db.sql("SELECT coalesce(ultimo_error, '') AS e FROM outbox_eventos WHERE aggregate_id = :id ORDER BY created_at LIMIT 1")
				.bind("id", id).map(f -> f.get("e", String.class)).one().block();
	}

}
