package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Alerta;
import java.time.Instant;
import java.util.UUID;

/** Alerta de escalamiento tal como se emite por SSE. Sin nombre del paciente. */
public record AlertaResponse(
		UUID eventId,
		UUID pacienteId,
		String nivel,
		int nivelNumero,
		long minutosEspera,
		int slaMinutos,
		Instant fechaRegistro,
		Instant fechaEscalamiento,
		String correlationId) {

	public static AlertaResponse desde(Alerta a) {
		return new AlertaResponse(a.eventId(), a.pacienteId(), a.nivel().name(), a.nivel().numero(), a.minutosEspera(),
				a.slaMinutos(), a.fechaRegistro(), a.fechaEscalamiento(), a.correlationId());
	}

}
