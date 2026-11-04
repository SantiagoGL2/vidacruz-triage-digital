package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Paciente;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Respuesta del RF-01. */
@Schema(description = "Paciente recién registrado y clasificado")
public record PacienteCreadoResponse(
		@Schema(description = "Identificador del paciente", example = "e62f5bb0-848c-4343-9887-19cc8fcf3a0a") UUID id,
		@Schema(description = "Nivel de triaje (RN-01)", example = "CRITICO",
				allowableValues = {"CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2"}) String nivel,
		@Schema(description = "Equivalente numérico del nivel (1 = más grave)", example = "1", minimum = "1", maximum = "5") int nivelNumero,
		@Schema(description = "SLA de espera en minutos para su nivel (RN-02), congelado al registrar", example = "5") int slaMinutos,
		@Schema(description = "Estado clínico inicial", example = "REGISTRADO",
				allowableValues = {"REGISTRADO", "EN_CONSULTA", "ALTA"}) String estado,
		@Schema(description = "Instante del registro (ISO 8601, UTC)", example = "2026-11-04T15:17:57.285937Z") Instant fechaRegistro) {

	public static PacienteCreadoResponse desde(Paciente p) {
		return new PacienteCreadoResponse(p.id(), p.nivel().name(), p.nivel().numero(), p.slaMinutos(),
				p.estado().name(), p.fechaRegistro());
	}

}
