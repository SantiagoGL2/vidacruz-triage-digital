package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.domain.model.RangosClinicos;
import com.vidacruz.triage.application.result.PacienteConsultado;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.ValoresApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Vista completa del paciente (GET y respuesta del PATCH). */
@Schema(description = "Datos completos del paciente, su estado actual y el tiempo en ese estado")
public record PacienteResponse(
		@Schema(description = EjemplosApi.DESC_ID_PACIENTE, example = EjemplosApi.ID_PACIENTE) UUID id,
		@Schema(description = "Nombre completo", example = "Carlos Andrés Pérez") String nombreCompleto,
		@Schema(description = "Edad en años", example = "58", minimum = "" + RangosClinicos.EDAD_MIN, maximum = "" + RangosClinicos.EDAD_MAX) int edad,
		SignosVitalesResponse signosVitales,
		@Schema(description = "Queja principal", example = "Dolor torácico") String quejaPrincipal,
		@Schema(description = "Nivel de triaje (RN-01)", example = ValoresApi.CRITICO,
				allowableValues = {ValoresApi.CRITICO, ValoresApi.URGENTE, ValoresApi.URGENTE_2, ValoresApi.NO_URGENTE, ValoresApi.NO_URGENTE_2}) String nivel,
		@Schema(description = "Nivel numérico (1 = más grave)", example = "1", minimum = "1", maximum = "5") int nivelNumero,
		@Schema(description = "SLA en minutos congelado al registrar (RN-02)", example = "5") int slaMinutos,
		@Schema(description = "Estado clínico actual", example = ValoresApi.EN_CONSULTA,
				allowableValues = {ValoresApi.REGISTRADO, ValoresApi.EN_CONSULTA, ValoresApi.ALTA}) String estado,
		@Schema(description = "true si superó su SLA sin entrar a consulta y fue escalado (RF-03); persistente", example = "false") boolean escalado,
		@Schema(description = "Instante del escalamiento; null si no fue escalado", nullable = true) Instant fechaEscalamiento,
		@Schema(description = "Instante del registro", example = EjemplosApi.FECHA_REGISTRO) Instant fechaRegistro,
		@Schema(description = "Instante de la primera entrada a consulta; null si aún no entró", nullable = true) Instant fechaPrimeraConsulta,
		@Schema(description = "Instante del último cambio de estado", example = EjemplosApi.FECHA_PRIMERA_CONSULTA) Instant fechaUltimoCambioEstado,
		@Schema(description = "Segundos transcurridos en el estado actual", example = "0") long tiempoEnEstadoSegundos,
		@Schema(description = "Minutos enteros transcurridos en el estado actual", example = "0") long tiempoEnEstadoMinutos) {

	@Schema(description = "Signos vitales medidos al registrar")
	public record SignosVitalesResponse(
			@Schema(description = "Frecuencia cardíaca (bpm)", example = "130", minimum = "" + RangosClinicos.FRECUENCIA_CARDIACA_MIN, maximum = "" + RangosClinicos.FRECUENCIA_CARDIACA_MAX) int frecuenciaCardiaca,
			@Schema(description = "Presión sistólica (mmHg)", example = "180", minimum = "" + RangosClinicos.PRESION_SISTOLICA_MIN, maximum = "" + RangosClinicos.PRESION_SISTOLICA_MAX) int presionSistolica,
			@Schema(description = "Presión diastólica (mmHg)", example = "110", minimum = "" + RangosClinicos.PRESION_DIASTOLICA_MIN, maximum = "" + RangosClinicos.PRESION_DIASTOLICA_MAX) int presionDiastolica,
			@Schema(description = "SpO2 (%)", example = "97", minimum = "" + RangosClinicos.SPO2_MIN, maximum = "" + RangosClinicos.SPO2_MAX) int spo2,
			@Schema(description = "Temperatura (°C)", example = "36.8", minimum = "" + RangosClinicos.TEMPERATURA_MIN, maximum = "" + RangosClinicos.TEMPERATURA_MAX) double temperatura,
			@Schema(description = "Dolor (" + RangosClinicos.DOLOR_MIN + "-" + RangosClinicos.DOLOR_MAX + ")", example = "8", minimum = "" + RangosClinicos.DOLOR_MIN, maximum = "" + RangosClinicos.DOLOR_MAX) int dolor,
			@Schema(description = "Glasgow (" + RangosClinicos.GCS_MIN + "-" + RangosClinicos.GCS_MAX + ")", example = "15", minimum = "" + RangosClinicos.GCS_MIN, maximum = "" + RangosClinicos.GCS_MAX) int gcs) {

		static SignosVitalesResponse desde(SignosVitales s) {
			return new SignosVitalesResponse(s.frecuenciaCardiaca(), s.presionSistolica(), s.presionDiastolica(), s.spo2(),
					s.temperatura(), s.dolor(), s.gcs());
		}

	}

	public static PacienteResponse desde(PacienteConsultado consultado) {
		return desde(consultado.paciente(), consultado.tiempoEnEstadoActual());
	}

	public static PacienteResponse desde(Paciente p, Duration tiempoEnEstado) {
		return new PacienteResponse(p.id(), p.nombreCompleto(), p.edad(), SignosVitalesResponse.desde(p.signosVitales()),
				p.quejaPrincipal(), p.nivel().name(), p.nivel().numero(), p.slaMinutos(), p.estado().name(),
				p.escalado(), p.fechaEscalamiento(), p.fechaRegistro(), p.fechaPrimeraConsulta(),
				p.fechaUltimoCambioEstado(), tiempoEnEstado.toSeconds(), tiempoEnEstado.toMinutes());
	}

}
