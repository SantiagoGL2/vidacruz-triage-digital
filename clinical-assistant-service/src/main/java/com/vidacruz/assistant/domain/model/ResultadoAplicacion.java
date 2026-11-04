package com.vidacruz.assistant.domain.model;

/** Que paso al intentar aplicar un evento a la vista. */
public enum ResultadoAplicacion {

	/** El evento cambio la vista. */
	APLICADO,
	/** El mismo eventId ya se habia procesado: no se vuelve a aplicar. */
	DUPLICADO,
	/** Consulta, escalamiento o alta de un paciente que no esta en la vista: se ignora (nunca contadores negativos). */
	IGNORADO_PACIENTE_DESCONOCIDO,
	/** Registro de un paciente que ya esta en la vista (con otro eventId): no suma dos veces. */
	IGNORADO_YA_REGISTRADO,
	/** Valido pero sin efecto, p. ej. escalar a un paciente que ya esta escalado. */
	SIN_CAMBIO

}
