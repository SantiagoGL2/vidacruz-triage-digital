package com.vidacruz.triage.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.triage.application.result.ReporteOperacion;
import com.vidacruz.triage.domain.model.HoraOperacion;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Respuesta del RF-05: una entrada por cada hora del rango, incluidas las vacias. Fechas en la zona del reporte. */
@Schema(description = "Reporte de operación por hora (RF-05)")
public record ReporteOperacionResponse(
		@Schema(description = "Inicio del rango (incluido), expresado en la zona del reporte", example = EjemplosApi.INICIO_REPORTE) OffsetDateTime desde,
		@Schema(description = "Fin del rango (excluido), expresado en la zona del reporte", example = EjemplosApi.FIN_REPORTE_PARAMETRO) OffsetDateTime hasta,
		@Schema(description = "Zona horaria IANA usada para agrupar por hora", example = "America/Bogota") String zonaHoraria,
		@Schema(description = "Una entrada por hora, ordenadas cronológicamente") List<HoraResponse> horas) {

	@Schema(description = "Métricas de los pacientes registrados en una hora")
	public record HoraResponse(
			@Schema(description = "Inicio de la hora (en la zona del reporte)", example = EjemplosApi.HORA_REPORTE) OffsetDateTime hora,
			@Schema(description = "Pacientes registrados en la hora", example = "12") long totalRegistrados,
			@Schema(description = "Registrados por nivel; siempre trae los 5 niveles",
					example = EjemplosApi.POR_NIVEL) Map<String, Long> porNivel,
			@Schema(description = "De los registrados en la hora, cuántos ya entraron a consulta", example = "9") long pacientesAtendidos,
			@Schema(description = "Espera promedio en minutos (registro -> primera consulta); null si nadie fue atendido",
					example = "18.4", nullable = true) Double esperaPromedioMinutos,
			@Schema(description = "Percentil 95 de la espera en minutos (interpolado); null si nadie fue atendido",
					example = "41.0", nullable = true) Double esperaP95Minutos) {

		static HoraResponse desde(HoraOperacion h) {
			Map<String, Long> porNivel = new LinkedHashMap<>();
			for (NivelTriaje nivel : NivelTriaje.values()) {
				porNivel.put(nivel.name(), h.porNivel().get(nivel));
			}
			return new HoraResponse(h.hora().toOffsetDateTime(), h.totalRegistrados(), porNivel, h.pacientesAtendidos(),
					h.esperaPromedioMinutos(), h.esperaP95Minutos());
		}

	}

	public static ReporteOperacionResponse desde(ReporteOperacion r) {
		return new ReporteOperacionResponse(
				r.desde().atZone(r.zonaHoraria()).toOffsetDateTime(),
				r.hasta().atZone(r.zonaHoraria()).toOffsetDateTime(),
				r.zonaHoraria().getId(),
				r.horas().stream().map(HoraResponse::desde).toList());
	}

}
