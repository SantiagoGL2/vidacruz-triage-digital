package com.vidacruz.triage.infrastructure.entrypoint.web.dto.request;

import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.ValoresApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de PATCH /triage/pacientes/{id}/estado. */
@Schema(description = "Estado clinico al que se quiere pasar al paciente")
public record CambiarEstadoRequest(
		@Schema(description = "Estado destino. Transiciones validas: REGISTRADO->EN_CONSULTA, REGISTRADO->ALTA, EN_CONSULTA->ALTA",
				example = ValoresApi.EN_CONSULTA, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull EstadoPaciente estado) {
}
