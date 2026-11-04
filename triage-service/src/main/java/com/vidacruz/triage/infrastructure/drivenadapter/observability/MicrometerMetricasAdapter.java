package com.vidacruz.triage.infrastructure.drivenadapter.observability;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class MicrometerMetricasAdapter implements MetricasPort {

	private final MeterRegistry registry;

	MicrometerMetricasAdapter(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void pacienteClasificado(NivelTriaje nivel) {
		registry.counter("triage.pacientes.clasificados", "nivel", nivel.name()).increment();
	}

}
