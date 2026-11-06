package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.ValoresApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Respuesta del RF-01. */
@Schema(description = "Paciente recién registrado y clasificado")
public record PacienteCreadoResponse(
		@Schema(description = EjemplosApi.DESC_ID_PACIENTE, example = EjemplosApi.ID_PACIENTE) UUID id,
		@Schema(description = "Nivel de triaje (RN-01)", example = ValoresApi.CRITICO,
				allowableValues = {ValoresApi.CRITICO, ValoresApi.URGENTE, ValoresApi.URGENTE_2, ValoresApi.NO_URGENTE, ValoresApi.NO_URGENTE_2}) String nivel,
		@Schema(description = "Equivalente numérico del nivel (1 = más grave)", example = "1", minimum = "1", maximum = "5") int nivelNumero,
		@Schema(description = "SLA de espera en minutos para su nivel (RN-02), congelado al registrar", example = "5") int slaMinutos,
		@Schema(description = "Estado clínico inicial", example = ValoresApi.REGISTRADO,
				allowableValues = {ValoresApi.REGISTRADO, ValoresApi.EN_CONSULTA, ValoresApi.ALTA}) String estado,
		@Schema(description = "Instante del registro (ISO 8601, UTC)", example = EjemplosApi.FECHA_REGISTRO) Instant fechaRegistro) {

	public static PacienteCreadoResponse desde(Paciente p) {
		return new PacienteCreadoResponse(p.id(), p.nivel().name(), p.nivel().numero(), p.slaMinutos(),
				p.estado().name(), p.fechaRegistro());
	}

}
