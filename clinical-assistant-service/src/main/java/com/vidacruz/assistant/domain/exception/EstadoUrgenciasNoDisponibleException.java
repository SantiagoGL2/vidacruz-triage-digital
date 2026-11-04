package com.vidacruz.assistant.domain.exception;

/**
 * El almacen de la vista (Redis) no responde. Es transitoria: el consumidor la reintenta con backoff y la API la
 * traduce a 503.
 */
public class EstadoUrgenciasNoDisponibleException extends RuntimeException {

	public EstadoUrgenciasNoDisponibleException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}

}
