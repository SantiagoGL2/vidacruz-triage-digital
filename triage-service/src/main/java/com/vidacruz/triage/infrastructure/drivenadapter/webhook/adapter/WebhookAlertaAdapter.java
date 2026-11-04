package com.vidacruz.triage.infrastructure.drivenadapter.webhook.adapter;

import com.vidacruz.triage.domain.model.Alerta;
import com.vidacruz.triage.domain.port.out.NotificadorAlertasPort;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import com.vidacruz.triage.infrastructure.drivenadapter.observability.NombresMetricas;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Canal webhook: POST JSON de la alerta a la URL configurada (vacia = deshabilitado). Timeout, Retry solo para
 * errores transitorios (timeout, conexion, 5xx, 429) y CircuitBreaker (Resilience4j). Si falla, se loguea y se
 * cuenta pero NO propaga el error: no bloquea el canal SSE. No lleva el nombre del paciente.
 */
@Slf4j
@Component
class WebhookAlertaAdapter implements NotificadorAlertasPort {

	/** Cuerpo del webhook. */
	record AlertaWebhook(UUID eventId, UUID pacienteId, String nivel, int nivelNumero, long minutosEspera,
			int slaMinutos, Instant fechaRegistro, Instant fechaEscalamiento, String correlationId) {
	}

	private final WebClient webClient;
	private final TriageAlertasProperties.Webhook config;
	private final MeterRegistry registry;
	private final CircuitBreaker circuitBreaker;
	private final Retry retry;

	WebhookAlertaAdapter(WebClient.Builder webClientBuilder, TriageAlertasProperties propiedades, MeterRegistry registry) {
		this.config = propiedades.webhook();
		this.registry = registry;
		this.webClient = webClientBuilder.build();
		this.circuitBreaker = CircuitBreaker.of("webhook-alertas", CircuitBreakerConfig.custom()
				.slidingWindowSize(config.ventanaCircuitBreaker())
				.minimumNumberOfCalls(Math.min(5, config.ventanaCircuitBreaker()))
				.failureRateThreshold(config.umbralFallos())
				.waitDurationInOpenState(config.esperaCircuitoAbierto())
				// un 4xx es un error de configuracion del receptor, no una caida: no abre el circuito
				.ignoreException(e -> e instanceof WebClientResponseException w && w.getStatusCode().is4xxClientError()
						&& w.getStatusCode().value() != 429)
				.build());
		this.retry = Retry.of("webhook-alertas", RetryConfig.<Object>custom()
				.maxAttempts(config.intentos())
				.waitDuration(config.esperaReintento())
				.retryOnException(WebhookAlertaAdapter::esTransitorio)
				.build());
	}

	@Override
	public Mono<Void> notificar(Alerta alerta) {
		if (!config.habilitado()) {
			return Mono.empty();
		}
		return Mono.defer(() -> webClient.post().uri(config.url())
						.bodyValue(cuerpo(alerta))
						.retrieve().toBodilessEntity()
						.timeout(config.timeout()))
				.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
				.transformDeferred(RetryOperator.of(retry))
				.doOnSuccess(r -> registry.counter(NombresMetricas.ALERTAS_EMITIDAS, NombresMetricas.TAG_CANAL, NombresMetricas.CANAL_WEBHOOK).increment())
				.onErrorResume(e -> {
					registry.counter(NombresMetricas.ALERTAS_FALLOS, NombresMetricas.TAG_CANAL, NombresMetricas.CANAL_WEBHOOK).increment();
					log.warn("Webhook de alertas fallo para paciente={}: {}", alerta.pacienteId(), e.toString());
					return Mono.empty();
				})
				.then();
	}

	private static boolean esTransitorio(Throwable e) {
		if (e instanceof CallNotPermittedException) {
			return false;
		}
		if (e instanceof WebClientResponseException w) {
			return w.getStatusCode().is5xxServerError() || w.getStatusCode().value() == 429;
		}
		return true; // timeout, conexion rechazada, reset...
	}

	private static AlertaWebhook cuerpo(Alerta a) {
		return new AlertaWebhook(a.eventId(), a.pacienteId(), a.nivel().name(), a.nivel().numero(), a.minutosEspera(),
				a.slaMinutos(), a.fechaRegistro(), a.fechaEscalamiento(), a.correlationId());
	}

}
