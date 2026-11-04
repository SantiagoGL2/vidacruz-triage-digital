package com.vidacruz.triage.infrastructure.drivenadapter.postgres.projection;

import java.util.UUID;

/** Solo el id de un paciente (candidatos al escalamiento). */
public record PacienteIdProjection(UUID id) {
}
