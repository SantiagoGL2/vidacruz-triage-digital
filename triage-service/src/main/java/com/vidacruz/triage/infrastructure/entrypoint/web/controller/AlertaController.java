package com.vidacruz.triage.infrastructure.entrypoint.web.controller;

import com.vidacruz.triage.application.port.in.SuscribirAlertasUseCase;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.AlertaResponse;
import org.springframework.http.MediaType;
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
class AlertaController {

	private final SuscribirAlertasUseCase suscribir;
	private final TriageAlertasProperties propiedades;

	AlertaController(SuscribirAlertasUseCase suscribir, TriageAlertasProperties propiedades) {
		this.suscribir = suscribir;
		this.propiedades = propiedades;
	}

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
