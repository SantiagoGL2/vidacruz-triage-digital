package com.vidacruz.assistant.domain.exception;

/**
 * El evento no se puede interpretar (campo obligatorio ausente o con formato invalido). No es recuperable: reintentar
 * no lo arregla, asi que el consumidor lo manda directo a la DLT.
 */
public class EventoInvalidoException extends RuntimeException {

	public EventoInvalidoException(String mensaje) {
		super(mensaje);
	}

	public EventoInvalidoException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}

}
