package com.vidacruz.triage.infrastructure.entrypoint.web.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de POST /triage/pacientes. Bean Validation solo exige presencia y tipo; los rangos clinicos
 * los valida el dominio (una unica fuente de verdad).
 */
public record RegistrarPacienteRequest(
		@NotNull String nombreCompleto,
		@NotNull Integer edad,
		@NotNull Integer frecuenciaCardiaca,
		@NotNull Integer presionSistolica,
		@NotNull Integer presionDiastolica,
		@NotNull Integer spo2,
		@NotNull Double temperatura,
		@NotNull Integer dolor,
		@NotNull Integer gcs,
		@NotNull String quejaPrincipal) {
}
