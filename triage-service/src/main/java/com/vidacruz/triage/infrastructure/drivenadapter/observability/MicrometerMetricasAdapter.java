package com.vidacruz.triage.infrastructure.drivenadapter.observability;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metricas de negocio. El gauge de pendientes del outbox lee un valor en memoria que actualiza cada ciclo del
 * publicador: asi el scrape de Prometheus nunca consulta la base ni bloquea.
 */
@Component
class MicrometerMetricasAdapter implements MetricasPort {

	private final MeterRegistry registry;
	private final AtomicLong pendientes = new AtomicLong();

	MicrometerMetricasAdapter(MeterRegistry registry) {
		this.registry = registry;
		Gauge.builder("triage.outbox.pendientes", pendientes, AtomicLong::get)
				.description("Eventos del outbox sin publicar (con intentos disponibles)").register(registry);
	}

	@Override
	public void pacienteClasificado(NivelTriaje nivel) {
		registry.counter("triage.pacientes.clasificados", "nivel", nivel.name()).increment();
	}

	@Override
	public void pacienteEscalado(NivelTriaje nivel) {
		registry.counter("triage.pacientes.escalados", "nivel", nivel.name()).increment();
	}

	@Override
	public void eventoPublicado() {
		registry.counter("triage.outbox.publicados").increment();
	}

	@Override
	public void fallaPublicacion() {
		registry.counter("triage.outbox.fallos").increment();
	}

	@Override
	public void pendientesOutbox(long valor) {
		pendientes.set(valor);
	}

}
