package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;

public interface MetricasPort {

	/** Cuenta un paciente clasificado (ya confirmado en base de datos). */
	void pacienteClasificado(NivelTriaje nivel);

	/** Cuenta un paciente escalado por SLA vencido (ya confirmado en base de datos). */
	void pacienteEscalado(NivelTriaje nivel);

	void eventoPublicado();

	void fallaPublicacion();

	/** Ultimo conteo de eventos pendientes del outbox (lo lee un gauge). */
	void pendientesOutbox(long pendientes);

}
