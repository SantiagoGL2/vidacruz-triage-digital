package com.vidacruz.triage.domain.model;

/** Tipo de evento para auditoria (RN-04) y para el evento Kafka correspondiente. */
public enum TipoEventoClinico {

	PACIENTE_REGISTRADO,
	PACIENTE_EN_CONSULTA,
	PACIENTE_ESCALADO,
	PACIENTE_ALTA;

	/** Nombre del evento en el topic triaje.eventos (p. ej. paciente_escalado). */
	public String nombreEvento() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

}
