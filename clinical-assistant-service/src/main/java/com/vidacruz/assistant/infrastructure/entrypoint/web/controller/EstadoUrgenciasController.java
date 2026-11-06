package com.vidacruz.assistant.infrastructure.entrypoint.web.controller;

import com.vidacruz.assistant.application.port.in.ConsultarEstadoUrgenciasUseCase;
import com.vidacruz.assistant.infrastructure.config.AsistenteEstadoUrgenciasProperties;
import com.vidacruz.assistant.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.assistant.infrastructure.entrypoint.web.dto.response.EstadoUrgenciasResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** RF-04 parte B: lee la vista materializada en Redis; nunca llama a la API de triage. */
@RestController
@RequestMapping("/asistente")
@Tag(name = "Estado de urgencias", description = "Vista en tiempo real de las urgencias, reconstruida desde los eventos de triage")
class EstadoUrgenciasController {

	private final ConsultarEstadoUrgenciasUseCase consultar;
	private final AsistenteEstadoUrgenciasProperties propiedades;

	EstadoUrgenciasController(ConsultarEstadoUrgenciasUseCase consultar, AsistenteEstadoUrgenciasProperties propiedades) {
		this.consultar = consultar;
		this.propiedades = propiedades;
	}

	@Operation(
			summary = "Estado actual de las urgencias",
			description = """
					**RF-04 parte B.** Devuelve cuantos pacientes hay activos (todos los que no estan en ALTA), \
					cuantos por nivel de triaje (siempre los 5 niveles) y cuantos estan escalados (RF-03). \
					La vista se reconstruye consumiendo el topic `triaje.eventos`: **este endpoint nunca llama a la API de triage**.

					**Consistencia eventual:** entre que triage registra un cambio y el asistente lo aplica pasan unos \
					milisegundos o segundos (o mas, si el consumidor esta atrasado o Redis estuvo caido). \
					`ultimoEventoEn` es el instante del ultimo evento aplicado: comparalo con `consultadoEn` para saber \
					que tan fresca es la vista. Si Redis no responde, la respuesta es 503.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Vista de urgencias.",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = EstadoUrgenciasResponse.class),
							examples = @ExampleObject(value = EjemplosApi.ESTADO_URGENCIAS))),
			@ApiResponse(responseCode = "401", description = EjemplosApi.DESC_401_API_KEY,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "500", description = EjemplosApi.DESC_500,
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500))),
			@ApiResponse(responseCode = "503", description = "Redis no esta disponible: la vista no se puede leer. Reintentar (header `Retry-After`).",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_503)))})
	@GetMapping("/estado-urgencias")
	Mono<EstadoUrgenciasResponse> estadoUrgencias() {
		return consultar.consultar().map(c -> EstadoUrgenciasResponse.desde(c, propiedades.zonaHoraria()));
	}

}
