package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Alerta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Alerta de escalamiento tal como se emite por SSE. Sin nombre del paciente. */
@Schema(description = "Alerta de escalamiento (RF-06). No incluye datos personales: consultar al paciente por id")
public record AlertaResponse(
		@Schema(description = "Id del evento paciente_escalado; sirve para deduplicar", example = "ac2bf6bf-3a64-4d0e-8d7e-0a8f9d6a7b21") UUID eventId,
		@Schema(description = "Paciente escalado", example = "e62f5bb0-848c-4343-9887-19cc8fcf3a0a") UUID pacienteId,
		@Schema(description = "Nivel de triaje", example = "CRITICO",
				allowableValues = {"CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2"}) String nivel,
		@Schema(description = "Nivel numérico (1 = más grave)", example = "1", minimum = "1", maximum = "5") int nivelNumero,
		@Schema(description = "Minutos enteros esperados entre el registro y el escalamiento", example = "6") long minutosEspera,
		@Schema(description = "SLA del paciente en minutos", example = "5") int slaMinutos,
		@Schema(description = "Instante del registro", example = "2026-11-04T15:17:57.285937Z") Instant fechaRegistro,
		@Schema(description = "Instante del escalamiento", example = "2026-11-04T15:23:30.100Z") Instant fechaEscalamiento,
		@Schema(description = "Correlation id del ciclo de escalamiento que lo originó", example = "escalamiento-sla-dc1f9056") String correlationId) {

	public static AlertaResponse desde(Alerta a) {
		return new AlertaResponse(a.eventId(), a.pacienteId(), a.nivel().name(), a.nivel().numero(), a.minutosEspera(),
				a.slaMinutos(), a.fechaRegistro(), a.fechaEscalamiento(), a.correlationId());
	}

}
