package com.vidacruz.triage.domain.event;

import java.util.UUID;

/**
 * Fila del outbox aun sin publicar. {@code payload} es el envelope JSON completo tal como se guardo: se publica
 * sin volver a serializarlo.
 */
public record EventoPendiente(
		UUID eventId,
		UUID aggregateId,
		String eventType,
		String payload,
		String correlationId,
		int intentos) {
}
