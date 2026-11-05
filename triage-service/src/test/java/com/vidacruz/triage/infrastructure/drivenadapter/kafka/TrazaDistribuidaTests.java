package com.vidacruz.triage.infrastructure.drivenadapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.support.AbstractProcesosTest;
import com.vidacruz.triage.support.ConsumidorDePrueba;
import com.vidacruz.triage.support.KafkaDePrueba;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Traza distribuida: una peticion HTTP, su escritura en PostgreSQL y la publicacion a Kafka (que ocurre despues, en otro
 * hilo, desde el outbox) deben quedar en UNA sola traza, y el mensaje debe llevar el {@code traceparent} para que el
 * consumidor la continue. Los spans se capturan en memoria con un {@code InMemorySpanExporter}.
 * <p>
 * Este test detecto que, sin un {@code ContextPropagators} W3C explicito, el propagador quedaba vacio (ni cabecera
 * {@code traceparent} en Kafka ni contexto restaurable): ver {@code PropagacionTrazasConfig}.
 */
@Import(TrazaDistribuidaTests.ExportadorEnMemoria.class)
class TrazaDistribuidaTests extends AbstractProcesosTest {

	static final KafkaDePrueba KAFKA = new KafkaDePrueba();

	@TestConfiguration
	static class ExportadorEnMemoria {

		static final InMemorySpanExporter EXPORTADOR = InMemorySpanExporter.create();

		@Bean
		SpanProcessor procesadorEnMemoria() {
			return SimpleSpanProcessor.create(EXPORTADOR);
		}

	}

	@DynamicPropertySource
	static void kafkaPropio(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
	}

	@Autowired
	PublicarEventosPendientesUseCase publicar;

	@BeforeEach
	void limpiar() {
		ExportadorEnMemoria.EXPORTADOR.reset();
	}

	private String traceparentDelOutbox(UUID id) {
		return db.sql("SELECT traceparent FROM outbox_eventos WHERE aggregate_id = :id").bind("id", id)
				.map(fila -> Optional.ofNullable(fila.get("traceparent", String.class)).orElse("(nulo)"))
				.one().block(Duration.ofSeconds(10));
	}

	private static String traceId(String traceparent) {
		// formato W3C: 00-<traceId 32 hex>-<spanId 16 hex>-<flags>
		assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
		return traceparent.split("-")[1];
	}

	@Test
	void laPeticionElOutboxYLaPublicacionAKafkaComparteUnaSolaTraza() throws Exception {
		UUID id = registrar(cuerpoNormal(), "corr-traza-1");

		// 1) El outbox guardo el traceparent de la peticion original.
		String traceparent = traceparentDelOutbox(id);
		String traza = traceId(traceparent);

		// 2) Se publica mas tarde, desde otro hilo: el mensaje lleva un traceparent de ESA MISMA traza.
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
			publicar.publicarPendientes().block(Duration.ofSeconds(60));
			return outbox(id).stream().allMatch(e -> e.get("_publicado").asBoolean());
		});
		try (var consumidor = new ConsumidorDePrueba(KAFKA.bootstrapServers(), "triaje.eventos")) {
			var registros = consumidor.leerHasta(Duration.ofSeconds(30),
					leidos -> leidos.stream().anyMatch(r -> id.toString().equals(r.key())));
			var mensaje = registros.stream().filter(r -> id.toString().equals(r.key())).findFirst().orElseThrow();
			assertThat(traceId(ConsumidorDePrueba.cabecera(mensaje, "traceparent"))).isEqualTo(traza);
		}

		// 3) Los spans exportados: servidor HTTP, consultas R2DBC, publicacion del outbox y envio a Kafka, todos con esa traza.
		List<SpanData> spans = ExportadorEnMemoria.EXPORTADOR.getFinishedSpanItems().stream()
				.filter(s -> s.getTraceId().equals(traza)).toList();
		List<String> nombres = spans.stream().map(SpanData::getName).toList();
		assertThat(nombres).as("spans de la traza %s", traza)
				.anyMatch(n -> n.startsWith("http post"))             // servidor HTTP (WebFlux)
				.anyMatch(n -> n.equals("query"))                      // consultas SQL (r2dbc-proxy)
				.anyMatch(n -> n.equals("outbox publicar"))            // span del publicador, hijo del traceparent guardado
				.anyMatch(n -> n.contains("triaje.eventos"));          // envio con KafkaTemplate

		// El span de publicacion cuelga del span de la peticion cuyo traceparent se guardo en el outbox.
		SpanData publicacion = spans.stream().filter(s -> s.getName().equals("outbox publicar")).findFirst().orElseThrow();
		assertThat(publicacion.getParentSpanId()).isEqualTo(traceparent.split("-")[2]);
	}

}
