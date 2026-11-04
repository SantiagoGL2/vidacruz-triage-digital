package com.vidacruz.assistant.domain.model;

import com.vidacruz.assistant.domain.model.enums.EstadoPaciente;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.UUID;

/** Paciente que esta en la vista (no ha sido dado de alta). Solo lo necesario para contar: sin datos personales. */
public record PacienteActivo(UUID id, NivelTriaje nivel, EstadoPaciente estado, boolean escalado, Instant fechaRegistro) {

	public PacienteActivo conEstado(EstadoPaciente nuevo) {
		return new PacienteActivo(id, nivel, nuevo, escalado, fechaRegistro);
	}

	public PacienteActivo escalar() {
		return new PacienteActivo(id, nivel, estado, true, fechaRegistro);
	}

}
