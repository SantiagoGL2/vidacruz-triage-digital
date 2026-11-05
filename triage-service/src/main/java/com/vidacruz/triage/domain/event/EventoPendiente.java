package com.vidacruz.triage.domain.event;

import java.util.UUID;

/**
 * Fila del outbox aun sin publicar. {@code payload} es el envelope JSON completo tal como se guardo: se publica
 * sin volver a serializarlo. {@code traceparent} es el contexto de traza W3C de la peticion que lo origino (puede ser
 * {@code null}): permite que la publicacion a Kafka quede en la misma traza.
 */
public record EventoPendiente(
		UUID eventId,
		UUID aggregateId,
		String eventType,
		String payload,
		String correlationId,
		int intentos,
		String traceparent) {
}
