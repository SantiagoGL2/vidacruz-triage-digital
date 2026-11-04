package com.vidacruz.assistant.domain.model;

import java.util.List;

/**
 * Veredicto de la barrera 3. {@code citasValidas} son las unicas que pueden aparecer como fuentes; si {@code valida}
 * es false la consulta termina en «sin protocolo».
 */
public record ValidacionRespuesta(boolean valida, String respuesta, List<CitaProtocolo> citasValidas, int citasPropuestas,
		int citasDescartadas, String motivo) {

	public ValidacionRespuesta {
		citasValidas = List.copyOf(citasValidas);
	}

}
