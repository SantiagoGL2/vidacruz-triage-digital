package com.vidacruz.triage.infrastructure.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.port.out.DeduplicacionEventosPort;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import com.vidacruz.triage.support.AbstractIntegrationTest;
import com.vidacruz.triage.support.ConsumidorDePrueba;
import com.vidacruz.triage.support.Contenedores;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * Un error inesperado DENTRO del procesamiento del consumidor de alertas (aqui, el puerto de deduplicacion falla) no se
 * pierde: el listener es sincrono por registro (ADR 0004), asi que el error llega al DefaultErrorHandler, se reintenta
 * con backoff sin confirmar el offset y, agotados los reintentos, el mensaje termina en la DLT. Un listener que
 * devolviera Mono perderia el evento (spring-kafka 3.3).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertasErrorInesperadoTests extends AbstractIntegrationTest {

	static final String TOPIC = "triaje.eventos";

	/** Intentos de procesar cada evento "venenoso" (por eventId). */
	static final Map<UUID, AtomicInteger> INTENTOS = new ConcurrentHashMap<>();

	@TestConfiguration
	static class DeduplicacionQueFalla {

		@Bean
		@Primary
		DeduplicacionEventosPort deduplicacionQueFalla() {
			return eventId -> {
				AtomicInteger intentos = INTENTOS.get(eventId);
				if (intentos != null) {
					intentos.incrementAndGet();
					return Mono.error(new IllegalStateException("fallo inesperado de prueba"));
				}
				return Mono.just(true);
			};
		}

	}

	@DynamicPropertySource
	static void consumidor(DynamicPropertyRegistry registry) {
		registry.add("triage.alertas.consumidor.habilitado", () -> "true");
		registry.add("triage.alertas.consumidor.group-id", () -> "alertas-error-" + UUID.randomUUID());
	}

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	TriageAlertasProperties alertas;

	@Test
	void unErrorInesperadoSeReintentaYTerminaEnLaDlt() throws Exception {
		UUID eventId = UUID.randomUUID();
		UUID pacienteId = UUID.randomUUID();
		INTENTOS.put(eventId, new AtomicInteger());
		var payload = new EventoTriaje.Payload(pacienteId, "CRITICO", 1, "REGISTRADO", "REGISTRADO",
				Instant.now().minusSeconds(900), 5, true, null, Instant.now());
		String json = objectMapper.writeValueAsString(
				new EventoTriaje(eventId, "paciente_escalado", Instant.now(), 1, "corr-error", payload));
		try (var dlt = new ConsumidorDePrueba(Contenedores.kafka().getBootstrapServers(), TOPIC + ".DLT")) {
			kafka.send(TOPIC, pacienteId.toString(), json).get();

			var enDlt = dlt.leerHasta(Duration.ofSeconds(45), leidos -> leidos.stream().anyMatch(r -> json.equals(r.value())));

			assertThat(enDlt).as("el mensaje que fallo termina en la DLT").anyMatch(r -> json.equals(r.value()));
			var registro = enDlt.stream().filter(r -> json.equals(r.value())).findFirst().orElseThrow();
			assertThat(ConsumidorDePrueba.cabecera(registro, "kafka_dlt-exception-fqcn")).isNotBlank();
		}
		// 1 entrega + los reintentos configurados: el error se reintento antes de rendirse
		await().atMost(Duration.ofSeconds(10)).untilAsserted(
				() -> assertThat(INTENTOS.get(eventId).get()).isEqualTo(1 + alertas.consumidor().reintentos()));
	}

	@Test
	void despuesDeUnMensajeVenenosoElConsumoSigueConElSiguiente() throws Exception {
		UUID venenoso = UUID.randomUUID();
		INTENTOS.put(venenoso, new AtomicInteger());
		UUID sano = UUID.randomUUID();
		for (UUID id : new UUID[] {venenoso, sano}) {
			var payload = new EventoTriaje.Payload(UUID.randomUUID(), "URGENTE", 2, "REGISTRADO", "REGISTRADO",
					Instant.now().minusSeconds(900), 10, true, null, Instant.now());
			kafka.send(TOPIC, id.toString(),
					objectMapper.writeValueAsString(new EventoTriaje(id, "paciente_escalado", Instant.now(), 1, "c", payload)))
					.get();
		}

		// el sano pasa por la deduplicacion (no es venenoso); el venenoso agota sus reintentos sin bloquear al otro
		await().atMost(Duration.ofSeconds(45)).untilAsserted(
				() -> assertThat(INTENTOS.get(venenoso).get()).isEqualTo(1 + alertas.consumidor().reintentos()));
		assertThat(INTENTOS.containsKey(sano)).isFalse();
	}

}
