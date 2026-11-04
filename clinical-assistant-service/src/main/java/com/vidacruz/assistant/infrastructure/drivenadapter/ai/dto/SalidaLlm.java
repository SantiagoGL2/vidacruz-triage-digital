package com.vidacruz.assistant.infrastructure.drivenadapter.ai.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** Salida estructurada que se le pide al modelo (BeanOutputConverter genera el esquema JSON a partir de este tipo). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonClassDescription("Respuesta basada exclusivamente en los fragmentos de protocolo entregados")
public record SalidaLlm(
		@JsonPropertyDescription("true solo si los fragmentos contienen la respuesta; false en cualquier otro caso")
		boolean respondida,
		@JsonPropertyDescription("Respuesta en español usando solo los fragmentos; cadena vacía si respondida es false")
		String respuesta,
		@JsonPropertyDescription("Fragmentos usados, con protocolo y sección exactos de la etiqueta; vacío si respondida es false")
		List<Cita> citas) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Cita(String protocolo, String seccion) {
	}

}
