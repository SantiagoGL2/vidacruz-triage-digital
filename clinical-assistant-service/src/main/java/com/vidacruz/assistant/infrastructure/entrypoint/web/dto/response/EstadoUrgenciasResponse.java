package com.vidacruz.assistant.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.assistant.application.result.EstadoUrgenciasConsultado;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
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
				example = "{\"CRITICO\":1,\"URGENTE\":2,\"URGENTE_2\":2,\"NO_URGENTE\":1,\"NO_URGENTE_2\":1}") Map<String, Long> porNivel,
		@Schema(description = "De los activos, cuantos estan escalados (superaron su SLA sin entrar a consulta)", example = "2") long escalados,
		@Schema(description = "Instante del ultimo evento aplicado a la vista. Indica que tan fresca es: si es muy anterior a "
				+ "`consultadoEn`, el asistente puede ir atrasado respecto a triage. null si aun no se ha aplicado ningun evento",
				example = "2026-11-04T10:15:00-05:00", nullable = true) OffsetDateTime ultimoEventoEn,
		@Schema(description = "Instante en que se leyo la vista", example = "2026-11-04T10:15:07.318-05:00") OffsetDateTime consultadoEn) {

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
