package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.application.result.PacienteConsultado;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Vista completa del paciente (GET y respuesta del PATCH). */
public record PacienteResponse(
		UUID id,
		String nombreCompleto,
		int edad,
		SignosVitalesResponse signosVitales,
		String quejaPrincipal,
		String nivel,
		int nivelNumero,
		int slaMinutos,
		String estado,
		boolean escalado,
		Instant fechaEscalamiento,
		Instant fechaRegistro,
		Instant fechaPrimeraConsulta,
		Instant fechaUltimoCambioEstado,
		long tiempoEnEstadoSegundos,
		long tiempoEnEstadoMinutos) {

	public record SignosVitalesResponse(int frecuenciaCardiaca, int presionSistolica, int presionDiastolica, int spo2,
			double temperatura, int dolor, int gcs) {

		static SignosVitalesResponse desde(SignosVitales s) {
			return new SignosVitalesResponse(s.frecuenciaCardiaca(), s.presionSistolica(), s.presionDiastolica(),
					s.spo2(), s.temperatura(), s.dolor(), s.gcs());
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
