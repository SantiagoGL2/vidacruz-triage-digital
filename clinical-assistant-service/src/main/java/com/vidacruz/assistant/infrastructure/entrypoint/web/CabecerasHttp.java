package com.vidacruz.assistant.infrastructure.entrypoint.web;

/** Cabeceras HTTP propias del servicio (las estandar se leen de {@code HttpHeaders}). */
public final class CabecerasHttp {

	/** Id de correlacion: se acepta del cliente (si es valido), se genera si falta y se devuelve en la respuesta. */
	public static final String CORRELATION_ID = "X-Correlation-Id";

	private CabecerasHttp() {
	}

}
