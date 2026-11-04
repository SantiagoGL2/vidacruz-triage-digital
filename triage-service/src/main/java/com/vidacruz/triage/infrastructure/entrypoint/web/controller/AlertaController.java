package com.vidacruz.triage.infrastructure.entrypoint.web.controller;

import com.vidacruz.triage.application.port.in.SuscribirAlertasUseCase;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc.EjemplosApi;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.AlertaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Stream SSE de alertas en tiempo real (RF-06). Protegido con X-API-Key como el resto de /triage/**.
 * Un comentario SSE de keep-alive cada {@code heartbeat} evita que proxies y balanceadores corten la conexion ociosa.
 */
@RestController
@RequestMapping("/triage/alertas")
@Tag(name = "Alertas", description = "Alertas en tiempo real al equipo medico cuando un paciente es escalado (RF-06)")
class AlertaController {

	private final SuscribirAlertasUseCase suscribir;
	private final TriageAlertasProperties propiedades;

	AlertaController(SuscribirAlertasUseCase suscribir, TriageAlertasProperties propiedades) {
		this.suscribir = suscribir;
		this.propiedades = propiedades;
	}

	@Operation(
			summary = "Stream SSE de alertas de escalamiento",
			description = """
					Abre un stream **Server-Sent Events** (`text/event-stream`) que emite una alerta por cada paciente \
					escalado por superar su SLA (**RF-03 / RN-03 / RF-06**). Cada evento SSE tiene `event: alerta`, `id` \
					igual al `eventId` y `data` con el JSON de `AlertaResponse` (sin el nombre del paciente: consultalo \
					con `GET /triage/pacientes/{id}`). Cada ~15 s llega un comentario `:keep-alive`.

					El canal es **efimero**: solo se reciben las alertas emitidas mientras el cliente esta conectado. \
					La conexion se queda abierta; Swagger UI no muestra bien los streams, usa `curl -N` (ver docs/eventos.md). \
					Decision completa en el ADR 0002.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Stream abierto. Cada evento es una alerta de escalamiento.",
					content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
							schema = @Schema(implementation = AlertaResponse.class),
							examples = @ExampleObject(name = "Evento SSE de una alerta", value = EjemplosApi.ALERTA_SSE))),
			@ApiResponse(responseCode = "401", description = "Falta el header `X-API-Key` o es invalido (la API key no se acepta por query string).",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_401))),
			@ApiResponse(responseCode = "500", description = "Error interno inesperado.",
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
							examples = @ExampleObject(value = EjemplosApi.ERROR_500)))})
	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Flux<ServerSentEvent<AlertaResponse>> stream() {
		Flux<ServerSentEvent<AlertaResponse>> alertas = suscribir.suscribir()
				.map(alerta -> ServerSentEvent.builder(AlertaResponse.desde(alerta))
						.event("alerta").id(alerta.eventId().toString()).build());
		Flux<ServerSentEvent<AlertaResponse>> latido = Flux.interval(propiedades.sse().heartbeat())
				.map(n -> ServerSentEvent.<AlertaResponse>builder().comment("keep-alive").build());
		return Flux.merge(alertas, latido);
	}

}
