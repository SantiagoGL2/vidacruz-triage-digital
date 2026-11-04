package com.vidacruz.triage.application.command;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.UUID;

/** Datos de un evento {@code paciente_escalado} necesarios para alertar. */
public record NotificarEscalamientoComando(
		UUID eventId,
		UUID pacienteId,
		NivelTriaje nivel,
		int slaMinutos,
		Instant fechaRegistro,
		Instant ocurridoEn,
		String correlationId) {
}
