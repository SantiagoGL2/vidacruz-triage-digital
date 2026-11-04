package com.vidacruz.assistant.infrastructure.entrypoint.kafka.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelope de {@code triaje.eventos} (contrato en docs/eventos.md). Copia deliberada del formato, no una libreria
 * compartida con triage-service (ADR 0003). El {@code payload} se deja como arbol JSON: solo se interpreta cuando el
 * {@code eventType} es uno conocido, asi un evento nuevo con otro payload no rompe la deserializacion.
 * Las propiedades desconocidas se ignoran (compatibilidad hacia adelante).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventoTriajeMensaje(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		Integer version,
		String correlationId,
		JsonNode payload) {
}
