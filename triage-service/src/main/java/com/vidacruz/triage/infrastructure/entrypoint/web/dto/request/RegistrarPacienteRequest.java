package com.vidacruz.triage.infrastructure.entrypoint.web.dto.request;

import com.vidacruz.triage.domain.model.RangosClinicos;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de POST /triage/pacientes. Bean Validation solo exige presencia y tipo; los rangos clinicos
 * los valida el dominio (una unica fuente de verdad). Los rangos del {@code @Schema} reproducen la tabla de
 * validaciones del enunciado (RF-01).
 */
@Schema(description = "Datos del paciente y sus signos vitales al llegar a urgencias")
public record RegistrarPacienteRequest(
		@Schema(description = "Nombre completo (" + RangosClinicos.NOMBRE_MIN + " a " + RangosClinicos.NOMBRE_MAX + " caracteres)", example = "Carlos Andrés Pérez",
				minLength = RangosClinicos.NOMBRE_MIN, maxLength = RangosClinicos.NOMBRE_MAX, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull String nombreCompleto,
		@Schema(description = "Edad en años", example = "58", minimum = "" + RangosClinicos.EDAD_MIN, maximum = "" + RangosClinicos.EDAD_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer edad,
		@Schema(description = "Frecuencia cardíaca (bpm)", example = "130", minimum = "" + RangosClinicos.FRECUENCIA_CARDIACA_MIN, maximum = "" + RangosClinicos.FRECUENCIA_CARDIACA_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer frecuenciaCardiaca,
		@Schema(description = "Presión sistólica (mmHg)", example = "180", minimum = "" + RangosClinicos.PRESION_SISTOLICA_MIN, maximum = "" + RangosClinicos.PRESION_SISTOLICA_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer presionSistolica,
		@Schema(description = "Presión diastólica (mmHg). Debe ser menor que la sistólica", example = "110",
				minimum = "" + RangosClinicos.PRESION_DIASTOLICA_MIN, maximum = "" + RangosClinicos.PRESION_DIASTOLICA_MAX, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer presionDiastolica,
		@Schema(description = "Saturación de oxígeno (%)", example = "97", minimum = "" + RangosClinicos.SPO2_MIN, maximum = "" + RangosClinicos.SPO2_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer spo2,
		@Schema(description = "Temperatura (°C)", example = "36.8", minimum = "" + RangosClinicos.TEMPERATURA_MIN, maximum = "" + RangosClinicos.TEMPERATURA_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Double temperatura,
		@Schema(description = "Dolor en escala " + RangosClinicos.DOLOR_MIN + " a " + RangosClinicos.DOLOR_MAX, example = "8", minimum = "" + RangosClinicos.DOLOR_MIN, maximum = "" + RangosClinicos.DOLOR_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer dolor,
		@Schema(description = "Escala de Glasgow (" + RangosClinicos.GCS_MIN + " a " + RangosClinicos.GCS_MAX + ")", example = "15", minimum = "" + RangosClinicos.GCS_MIN, maximum = "" + RangosClinicos.GCS_MAX,
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull Integer gcs,
		@Schema(description = "Queja principal en texto libre. Las administrativas (documentos, recolección de resultados, "
				+ "renovación de recetario, vacunación, seguimiento) clasifican como NO_URGENTE_2",
				example = "Dolor torácico", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull String quejaPrincipal) {
}
