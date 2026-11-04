package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

/** Nombres de las propiedades extra del {@code ProblemDetail} (cuerpo de error) que forman parte del contrato de la API. */
public final class PropiedadesProblema {

	public static final String CORRELATION_ID = "correlationId";
	public static final String TIMESTAMP = "timestamp";
	public static final String ERRORES = "errores";
	public static final String OPERACION = "operacion";

	private PropiedadesProblema() {
	}

}
