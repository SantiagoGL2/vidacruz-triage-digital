package com.vidacruz.assistant.infrastructure.entrypoint.web.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de POST /asistente/consulta. */
@Schema(description = "Pregunta sobre los protocolos clínicos")
public record ConsultaRequest(
		@Schema(description = "Pregunta en lenguaje natural (5 a 500 caracteres, no puede ser solo espacios)",
				example = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?",
				minLength = 5, maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "campo obligatorio y no puede estar en blanco")
		@Size(min = 5, max = 500, message = "debe tener entre 5 y 500 caracteres")
		String pregunta) {
}
