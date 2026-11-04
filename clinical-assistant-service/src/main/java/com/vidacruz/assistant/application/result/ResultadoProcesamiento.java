package com.vidacruz.assistant.application.result;

/** Que hizo el asistente con un evento recibido. */
public enum ResultadoProcesamiento {

	APLICADO,
	DUPLICADO,
	IGNORADO_TIPO_DESCONOCIDO,
	IGNORADO_PACIENTE_DESCONOCIDO,
	IGNORADO_YA_REGISTRADO,
	SIN_CAMBIO

}
