package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.UUID;

/**
 * Alerta para el equipo medico (RF-06): un paciente supero su SLA sin entrar a consulta.
 * No lleva el nombre del paciente (dato sensible): el equipo lo consulta por id en la API.
 */
public record Alerta(
		UUID eventId,
		UUID pacienteId,
		NivelTriaje nivel,
		long minutosEspera,
		int slaMinutos,
		Instant fechaRegistro,
		Instant fechaEscalamiento,
		String correlationId) {
}
