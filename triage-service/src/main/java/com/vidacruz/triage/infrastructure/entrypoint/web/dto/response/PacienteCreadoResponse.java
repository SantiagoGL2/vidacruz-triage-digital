package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.Paciente;
import java.time.Instant;
import java.util.UUID;

/** Respuesta del RF-01. */
public record PacienteCreadoResponse(UUID id, String nivel, int nivelNumero, int slaMinutos, String estado,
		Instant fechaRegistro) {

	public static PacienteCreadoResponse desde(Paciente p) {
		return new PacienteCreadoResponse(p.id(), p.nivel().name(), p.nivel().numero(), p.slaMinutos(),
				p.estado().name(), p.fechaRegistro());
	}

}
