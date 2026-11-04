package com.vidacruz.assistant.application.command;

import java.time.Instant;
import java.util.UUID;

/**
 * Datos crudos de un evento de {@code triaje.eventos} tal como los lee el consumidor. {@code eventType} puede ser un
 * tipo que este servicio no conoce; los campos del payload son texto y los valida el dominio.
 */
public record AplicarEventoComando(
		UUID eventId,
		String eventType,
		Instant occurredAt,
		String correlationId,
		String pacienteId,
		String nivel,
		String fechaRegistro) {
}
