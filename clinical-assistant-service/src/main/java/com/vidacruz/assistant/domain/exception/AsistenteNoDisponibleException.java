package com.vidacruz.assistant.domain.exception;

/**
 * El proveedor de IA (chat o embeddings) no puede atender la consulta: circuito abierto, timeout, reintentos agotados,
 * demasiadas llamadas concurrentes o error de configuracion. Modo degradado: la API responde 503.
 */
public class AsistenteNoDisponibleException extends RuntimeException {

	private final String operacion;

	public AsistenteNoDisponibleException(String operacion, String motivo, Throwable causa) {
		super("El proveedor de IA no esta disponible (" + operacion + "): " + motivo, causa);
		this.operacion = operacion;
	}

	/** {@code chat} o {@code embedding}. */
	public String operacion() {
		return operacion;
	}

}
