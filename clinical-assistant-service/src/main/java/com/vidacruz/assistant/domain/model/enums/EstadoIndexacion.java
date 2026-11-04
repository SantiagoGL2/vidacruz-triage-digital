package com.vidacruz.assistant.domain.model.enums;

/** Fase de la indexacion de los protocolos en esta replica. Solo {@link #LISTO} permite consultar. */
public enum EstadoIndexacion {

	/** Aun no termino la primera indexacion (o se reintenta sin haber tenido exito todavia). */
	INDEXANDO,
	/** El indice esta sincronizado con el corpus. */
	LISTO,
	/** La ultima indexacion fallo; se reintenta con backoff. */
	FALLIDO

}
