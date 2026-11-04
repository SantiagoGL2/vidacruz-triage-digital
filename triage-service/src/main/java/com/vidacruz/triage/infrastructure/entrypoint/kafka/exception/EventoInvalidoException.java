package com.vidacruz.triage.infrastructure.entrypoint.kafka.exception;

/** Mensaje de triaje.eventos que no se puede interpretar: no se reintenta, va directo a la DLT. */
public class EventoInvalidoException extends RuntimeException {

	public EventoInvalidoException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}

	public EventoInvalidoException(String mensaje) {
		super(mensaje);
	}

}
