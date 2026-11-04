package com.vidacruz.triage.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope del evento (ver docs/eventos.md). {@code eventId} es el id de la fila del outbox y el que usara el
 * consumidor para deduplicar.
 */
public record EventoTriaje(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		int version,
		String correlationId,
		Payload payload) {

	/** Lo que necesita el asistente para reconstruir el conteo de activos por nivel sin llamar a la API de triage. */
	public record Payload(
			UUID pacienteId,
			String nivel,
			int nivelNumero,
			String estadoAnterior,
			String estadoNuevo,
			Instant fechaRegistro,
			int slaMinutos,
			boolean escalado,
			Instant fechaPrimeraConsulta,
			Instant fechaCambioEstado) {
	}

}
