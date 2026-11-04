package com.vidacruz.triage.infrastructure.entrypoint.web.dto.request;

import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de PATCH /triage/pacientes/{id}/estado. */
public record CambiarEstadoRequest(@NotNull EstadoPaciente estado) {
}
