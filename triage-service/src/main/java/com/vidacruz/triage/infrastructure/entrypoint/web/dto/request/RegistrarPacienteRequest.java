package com.vidacruz.triage.infrastructure.entrypoint.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de POST /triage/pacientes. Bean Validation solo exige presencia y tipo; los rangos clinicos
 * los valida el dominio (una unica fuente de verdad). Los rangos del {@code @Schema} reproducen la tabla de
 * validaciones del enunciado (RF-01).
 */
@Schema(description = "Datos del paciente y sus signos vitales al llegar a urgencias")
public record RegistrarPacienteRequest(
		@Schema(description = "Nombre completo (3 a 100 caracteres)", example = "Carlos Andrés Pérez",
				minLength = 3, maxLength = 100, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull String nombreCompleto,
		@Schema(description = "Edad en años", example = "58", minimum = "0", maximum = "120",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer edad,
		@Schema(description = "Frecuencia cardíaca (bpm)", example = "130", minimum = "20", maximum = "250",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer frecuenciaCardiaca,
		@Schema(description = "Presión sistólica (mmHg)", example = "180", minimum = "60", maximum = "260",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer presionSistolica,
		@Schema(description = "Presión diastólica (mmHg). Debe ser menor que la sistólica", example = "110",
				minimum = "30", maximum = "160", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer presionDiastolica,
		@Schema(description = "Saturación de oxígeno (%)", example = "97", minimum = "70", maximum = "100",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer spo2,
		@Schema(description = "Temperatura (°C)", example = "36.8", minimum = "34.0", maximum = "42.0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Double temperatura,
		@Schema(description = "Dolor en escala 0 a 10", example = "8", minimum = "0", maximum = "10",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer dolor,
		@Schema(description = "Escala de Glasgow (3 a 15)", example = "15", minimum = "3", maximum = "15",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer gcs,
		@Schema(description = "Queja principal en texto libre. Las administrativas (documentos, recolección de resultados, "
				+ "renovación de recetario, vacunación, seguimiento) clasifican como NO_URGENTE_2",
				example = "Dolor torácico", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull String quejaPrincipal) {
}
