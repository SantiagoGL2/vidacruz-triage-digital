package com.vidacruz.triage.infrastructure.drivenadapter.kafka;

/** Cabeceras de cada mensaje de {@code triaje.eventos} (contrato en docs/eventos.md). */
public final class CabecerasKafka {

	public static final String EVENT_ID = "eventId";
	public static final String EVENT_TYPE = "eventType";
	public static final String CORRELATION_ID = "correlationId";

	private CabecerasKafka() {
	}

}
