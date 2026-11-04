package com.vidacruz.assistant.infrastructure.entrypoint.web.dto.response;

import com.vidacruz.assistant.application.result.RespuestaConsulta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Respuesta de POST /asistente/consulta (formato del enunciado). */
@Schema(description = "Respuesta basada en los protocolos del corpus, con sus fuentes. Si no hay protocolo relevante, "
		+ "`respuesta` es el aviso de que no existe protocolo disponible y `fuentes` es una lista vacía.")
public record ConsultaResponse(
		@Schema(description = "Respuesta en español, construida solo con el contenido de las fuentes citadas",
				example = "Reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la primera hora. Vigilar signos de encefalopatía.")
		String respuesta,
		@Schema(description = "Fuentes validadas del corpus (protocolo y sección). Vacía solo cuando no hay protocolo disponible")
		List<FuenteResponse> fuentes) {

	@Schema(description = "Protocolo y sección del corpus que sustentan la respuesta")
	public record FuenteResponse(
			@Schema(description = "Identificador del protocolo", example = "PROTO-001") String protocolo,
			@Schema(description = "Sección del protocolo", example = "Medidas iniciales") String seccion) {
	}

	public static ConsultaResponse desde(RespuestaConsulta r) {
		return new ConsultaResponse(r.respuesta(),
				r.fuentes().stream().map(c -> new FuenteResponse(c.protocolo(), c.seccion())).toList());
	}

}
