package com.vidacruz.assistant.infrastructure.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.EventosTriaje;
import com.vidacruz.assistant.support.KafkaDePrueba;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Grupo de consumidores PROPIO y contexto descartable: este test necesita su propio exportador de spans (por eso un contexto
 * distinto del compartido). Si compartiera el {@code group-id}, Kafka repartiria las particiones entre ambos contextos vivos y
 * el mensaje podria consumirlo el otro (sin exportador). Con {@code earliest} el grupo nuevo relee el topic entero, sin
 * efecto: solo se afirma sobre los spans de la traza de este mensaje, y {@code @DirtiesContext} lo cierra al terminar.
 * <p>
 * Traza distribuida en el consumidor: el mensaje llega con el {@code traceparent} que puso triage; el consumo y los comandos
 * de Redis que aplica deben continuar ESA traza (mismo traceId), no abrir una nueva. Spans capturados en memoria.
 */
@Import(TrazaDistribuidaTests.ExportadorEnMemoria.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "assistant.kafka.group-id=asistente-traza-test")
class TrazaDistribuidaTests extends AbstractIntegrationTest {

	@TestConfiguration
	static class ExportadorEnMemoria {

		static final InMemorySpanExporter EXPORTADOR = InMemorySpanExporter.create();

		@Bean
		SpanProcessor procesadorEnMemoria() {
			return SimpleSpanProcessor.create(EXPORTADOR);
		}

	}

	@BeforeEach
	void limpiar() {
		ExportadorEnMemoria.EXPORTADOR.reset();
	}

	@Test
	void elConsumoYLosComandosDeRedisContinuanLaTrazaQueTraeElMensaje() {
		String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		String spanPadre = "00f067aa0ba902b7";
		UUID id = UUID.randomUUID();

		KafkaDePrueba.publicar(EventosTriaje.registrado(id, "URGENTE"),
				Map.of("traceparent", "00-" + traceId + "-" + spanPadre + "-01"));

		await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
			List<SpanData> spans = ExportadorEnMemoria.EXPORTADOR.getFinishedSpanItems().stream()
					.filter(s -> s.getTraceId().equals(traceId)).toList();
			// Consumo: span CONSUMER cuyo padre es el span que traia el mensaje (la traza continua, no se abre una nueva).
			SpanData consumo = spans.stream().filter(sp -> sp.getName().equals("triaje.eventos receive")).findFirst()
					.orElseThrow(() -> new AssertionError("sin span de consumo en la traza " + traceId + ": "
							+ spans.stream().map(SpanData::getName).toList()));
			assertThat(consumo.getKind()).isEqualTo(SpanKind.CONSUMER);
			assertThat(consumo.getParentSpanId()).isEqualTo(spanPadre);
			// Redis: los comandos del script Lua cuelgan del span de consumo.
			assertThat(spans).as("comandos Redis dentro de la traza")
					.anyMatch(sp -> (sp.getName().equals("eval") || sp.getName().equals("evalsha"))
							&& sp.getKind() == SpanKind.CLIENT && sp.getParentSpanId().equals(consumo.getSpanId()));
		});
	}

}
