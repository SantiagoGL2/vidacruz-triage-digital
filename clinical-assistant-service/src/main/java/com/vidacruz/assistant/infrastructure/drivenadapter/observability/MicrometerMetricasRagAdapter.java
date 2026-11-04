package com.vidacruz.assistant.infrastructure.drivenadapter.observability;

import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Metricas del RAG: {@code asistente.consultas{resultado}}, {@code asistente.citas.descartadas},
 * {@code asistente.llm.latencia{operacion,resultado}} (Timer) y {@code asistente.llm.tokens{tipo}}.
 * El estado de los circuit breakers lo publica Resilience4j ({@code resilience4j_circuitbreaker_state}).
 */
@Component
class MicrometerMetricasRagAdapter implements MetricasRagPort {

	private final MeterRegistry registry;

	MicrometerMetricasRagAdapter(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void consulta(String resultado) {
		registry.counter(NombresMetricas.CONSULTAS, NombresMetricas.TAG_RESULTADO, resultado).increment();
	}

	@Override
	public void citasDescartadas(int cantidad) {
		registry.counter(NombresMetricas.CITAS_DESCARTADAS).increment(cantidad);
	}

	@Override
	public void latenciaLlm(String operacion, String resultado, Duration duracion) {
		Timer.builder(NombresMetricas.LLM_LATENCIA).description("Latencia de cada llamada al proveedor de IA")
				.tag(NombresMetricas.TAG_OPERACION, operacion).tag(NombresMetricas.TAG_RESULTADO, resultado).register(registry).record(duracion);
	}

	@Override
	public void tokens(String tipo, long cantidad) {
		registry.counter(NombresMetricas.LLM_TOKENS, NombresMetricas.TAG_TIPO, tipo).increment(cantidad);
	}

}
