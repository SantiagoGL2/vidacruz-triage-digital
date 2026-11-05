package com.vidacruz.triage.infrastructure.drivenadapter.postgres.projection;

import java.util.UUID;

/** Columnas que necesita el publicador del outbox (no es una fila completa de {@code outbox_eventos}). */
public record EventoPendienteProjection(
		UUID id,
		UUID aggregateId,
		String eventType,
		String payload,
		String correlationId,
		int intentos,
		String traceparent) {
}
