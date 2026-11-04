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
		Gauge.builder(NombresMetricas.OUTBOX_PENDIENTES, pendientes, AtomicLong::get)
				.description("Eventos del outbox sin publicar (con intentos disponibles)").register(registry);
	}

	@Override
	public void pacienteClasificado(NivelTriaje nivel) {
		registry.counter(NombresMetricas.PACIENTES_CLASIFICADOS, NombresMetricas.TAG_NIVEL, nivel.name()).increment();
	}

	@Override
	public void pacienteEscalado(NivelTriaje nivel) {
		registry.counter(NombresMetricas.PACIENTES_ESCALADOS, NombresMetricas.TAG_NIVEL, nivel.name()).increment();
	}

	@Override
	public void eventoPublicado() {
		registry.counter(NombresMetricas.OUTBOX_PUBLICADOS).increment();
	}

	@Override
	public void fallaPublicacion() {
		registry.counter(NombresMetricas.OUTBOX_FALLOS).increment();
	}

	@Override
	public void pendientesOutbox(long valor) {
		pendientes.set(valor);
	}

}
