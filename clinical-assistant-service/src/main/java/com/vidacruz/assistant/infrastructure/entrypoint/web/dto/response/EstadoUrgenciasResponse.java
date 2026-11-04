package com.vidacruz.assistant.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.assistant.application.result.EstadoUrgenciasConsultado;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Respuesta de GET /asistente/estado-urgencias. Fechas en la zona horaria configurada. */
@Schema(description = "Estado de urgencias en tiempo real, reconstruido a partir de los eventos de triage (consistencia eventual)")
public record EstadoUrgenciasResponse(
		@Schema(description = "Pacientes activos: todos los que no estan en ALTA (REGISTRADO + EN_CONSULTA)", example = "7") long totalActivos,
		@Schema(description = "Activos por nivel de triaje; siempre trae los 5 niveles",
				example = EjemplosApi.POR_NIVEL) Map<String, Long> porNivel,
		@Schema(description = "De los activos, cuantos estan escalados (superaron su SLA sin entrar a consulta)", example = "2") long escalados,
		@Schema(description = "Instante del ultimo evento aplicado a la vista. Indica que tan fresca es: si es muy anterior a "
				+ "`consultadoEn`, el asistente puede ir atrasado respecto a triage. null si aun no se ha aplicado ningun evento",
				example = EjemplosApi.ULTIMO_EVENTO_EN, nullable = true) OffsetDateTime ultimoEventoEn,
		@Schema(description = "Instante en que se leyo la vista", example = EjemplosApi.CONSULTADO_EN) OffsetDateTime consultadoEn) {

	public static EstadoUrgenciasResponse desde(EstadoUrgenciasConsultado consultado, ZoneId zona) {
		EstadoUrgencias estado = consultado.estado();
		Map<String, Long> porNivel = new LinkedHashMap<>();
		for (NivelTriaje nivel : NivelTriaje.values()) {
			porNivel.put(nivel.name(), estado.porNivel().get(nivel));
		}
		return new EstadoUrgenciasResponse(
				estado.totalActivos(),
				porNivel,
				estado.escalados(),
				estado.ultimoEventoEn() == null ? null : estado.ultimoEventoEn().atZone(zona).toOffsetDateTime(),
				consultado.consultadoEn().atZone(zona).toOffsetDateTime());
	}

}
