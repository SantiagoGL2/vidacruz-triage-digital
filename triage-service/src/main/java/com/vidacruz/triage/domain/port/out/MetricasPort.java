package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;

public interface MetricasPort {

	/** Cuenta un paciente clasificado (ya confirmado en base de datos). */
	void pacienteClasificado(NivelTriaje nivel);

}
