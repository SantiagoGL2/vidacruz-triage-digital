package com.vidacruz.assistant.domain.model;

/** Resultado de pedirle una respuesta al modelo: una salida con la forma esperada, o una que no se pudo interpretar. */
public sealed interface SalidaGenerador {

	record Generada(RespuestaGenerada respuesta) implements SalidaGenerador {
	}

	/** La salida no tiene la estructura pedida (JSON invalido, vacia...). Se trata como «sin protocolo». */
	record NoParseable(String motivo) implements SalidaGenerador {
	}

}
