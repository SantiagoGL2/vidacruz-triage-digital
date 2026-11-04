package com.vidacruz.assistant.infrastructure.entrypoint.kafka;

/** Cabeceras que el asistente lee de cada mensaje de {@code triaje.eventos} (contrato en docs/eventos.md). */
public final class CabecerasKafka {

	public static final String CORRELATION_ID = "correlationId";

	private CabecerasKafka() {
	}

}
