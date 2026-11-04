package com.vidacruz.assistant.application.result;

import com.vidacruz.assistant.domain.model.CitaProtocolo;
import java.util.List;

/** Respuesta de la consulta: texto y fuentes (vacias solo en la respuesta de «sin protocolo»). */
public record RespuestaConsulta(String respuesta, List<CitaProtocolo> fuentes) {

	public RespuestaConsulta {
		fuentes = List.copyOf(fuentes);
	}

}
