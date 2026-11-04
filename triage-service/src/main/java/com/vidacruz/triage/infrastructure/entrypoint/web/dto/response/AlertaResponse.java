package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Alerta;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.ValoresApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Alerta de escalamiento tal como se emite por SSE. Sin nombre del paciente. */
@Schema(description = "Alerta de escalamiento (RF-06). No incluye datos personales: consultar al paciente por id")
public record AlertaResponse(
		@Schema(description = "Id del evento paciente_escalado; sirve para deduplicar", example = EjemplosApi.ID_EVENTO_ALERTA) UUID eventId,
		@Schema(description = "Paciente escalado", example = EjemplosApi.ID_PACIENTE) UUID pacienteId,
		@Schema(description = "Nivel de triaje", example = ValoresApi.CRITICO,
				allowableValues = {ValoresApi.CRITICO, ValoresApi.URGENTE, ValoresApi.URGENTE_2, ValoresApi.NO_URGENTE, ValoresApi.NO_URGENTE_2}) String nivel,
		@Schema(description = "Nivel numérico (1 = más grave)", example = "1", minimum = "1", maximum = "5") int nivelNumero,
		@Schema(description = "Minutos enteros esperados entre el registro y el escalamiento", example = "6") long minutosEspera,
		@Schema(description = "SLA del paciente en minutos", example = "5") int slaMinutos,
		@Schema(description = "Instante del registro", example = EjemplosApi.FECHA_REGISTRO) Instant fechaRegistro,
		@Schema(description = "Instante del escalamiento", example = EjemplosApi.FECHA_ESCALAMIENTO) Instant fechaEscalamiento,
		@Schema(description = "Correlation id del ciclo de escalamiento que lo originó", example = EjemplosApi.CORRELATION_ESCALAMIENTO) String correlationId) {

	public static AlertaResponse desde(Alerta a) {
		return new AlertaResponse(a.eventId(), a.pacienteId(), a.nivel().name(), a.nivel().numero(), a.minutosEspera(),
				a.slaMinutos(), a.fechaRegistro(), a.fechaEscalamiento(), a.correlationId());
	}

}
