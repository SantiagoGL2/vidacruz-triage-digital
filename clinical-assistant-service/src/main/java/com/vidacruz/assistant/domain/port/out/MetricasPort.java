package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.EstadoUrgencias;

/** Metricas de negocio de la proyeccion (el adapter las registra en Micrometer). */
public interface MetricasPort {

	/** Un evento cambio la vista (o fue valido sin efecto). {@code tipoEvento} = eventType del envelope. */
	void eventoProcesado(String tipoEvento);

	void eventoDuplicado();

	/** {@code motivo}: tipo_desconocido, paciente_desconocido o ya_registrado. */
	void eventoIgnorado(String motivo);

	/** Un mensaje termino en la DLT. */
	void eventoEnviadoADlt();

	/** Refresca el gauge de pacientes activos por nivel con la ultima vista conocida. */
	void vistaActualizada(EstadoUrgencias vista);

}
