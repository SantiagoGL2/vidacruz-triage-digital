package com.vidacruz.assistant.domain.model;

import java.util.List;

/**
 * Lo que propone el modelo de lenguaje, SIN validar: puede estar mal, citar fragmentos que no existen o decir que no
 * pudo responder. Solo {@code ValidadorRespuesta} decide si es utilizable.
 */
public record RespuestaGenerada(boolean respondida, String respuesta, List<CitaProtocolo> citas) {

	public RespuestaGenerada {
		citas = citas == null ? List.of() : citas.stream().filter(java.util.Objects::nonNull).toList();
	}

}
