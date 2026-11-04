package com.vidacruz.assistant.infrastructure.drivenadapter.observability;

import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metricas Micrometer de la proyeccion:
 * {@code asistente.eventos.procesados{eventType}}, {@code .duplicados}, {@code .ignorados{motivo}}, {@code .dlt} y los
 * gauges {@code asistente.pacientes.activos{nivel}} y {@code asistente.pacientes.escalados}.
 * <p>
 * Los gauges guardan la ultima vista que esta replica conoce (la que devolvio Redis al aplicar un evento o al
 * consultar): con varias replicas todas publican el mismo valor global, asi que en Prometheus hay que agregarlos con
 * max o avg, no con sum.
 */
@Component
class MicrometerMetricasAdapter implements MetricasPort {

	private final MeterRegistry registry;
	private final Map<NivelTriaje, AtomicLong> activosPorNivel = new EnumMap<>(NivelTriaje.class);
	private final AtomicLong escalados = new AtomicLong();

	MicrometerMetricasAdapter(MeterRegistry registry) {
		this.registry = registry;
		for (NivelTriaje nivel : NivelTriaje.values()) {
			AtomicLong valor = new AtomicLong();
			activosPorNivel.put(nivel, valor);
			Gauge.builder("asistente.pacientes.activos", valor, AtomicLong::get)
					.description("Pacientes activos (no en ALTA) en la vista de urgencias, por nivel")
					.tag("nivel", nivel.name()).register(registry);
		}
		Gauge.builder("asistente.pacientes.escalados", escalados, AtomicLong::get)
				.description("Pacientes activos escalados en la vista de urgencias").register(registry);
	}

	@Override
	public void eventoProcesado(String tipoEvento) {
		registry.counter("asistente.eventos.procesados", "eventType", tipoEvento).increment();
	}

	@Override
	public void eventoDuplicado() {
		registry.counter("asistente.eventos.duplicados").increment();
	}

	@Override
	public void eventoIgnorado(String motivo) {
		registry.counter("asistente.eventos.ignorados", "motivo", motivo).increment();
	}

	@Override
	public void eventoEnviadoADlt() {
		registry.counter("asistente.eventos.dlt").increment();
	}

	@Override
	public void vistaActualizada(EstadoUrgencias vista) {
		vista.porNivel().forEach((nivel, n) -> activosPorNivel.get(nivel).set(n));
		escalados.set(vista.escalados());
	}

}
