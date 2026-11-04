package com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience;

import com.vidacruz.assistant.domain.exception.AsistenteNoDisponibleException;
import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Ejecuta una llamada BLOQUEANTE al proveedor de IA (Spring AI) sin bloquear el event loop y con Resilience4j.
 * <p>
 * Orden (de dentro hacia fuera): Bulkhead -> TimeLimiter -> CircuitBreaker -> Retry. El Retry envuelve al circuit breaker,
 * asi cada intento cuenta como una llamada para el circuito y un circuito abierto corta los reintentos al instante. Las
 * instancias son {@code llm-chat} y {@code llm-embedding} (application.yml). Cualquier fallo final (circuito abierto,
 * timeout, reintentos agotados, bulkhead lleno, 4xx de configuracion) se traduce a {@link AsistenteNoDisponibleException},
 * que la API convierte en 503: es el fallback explicito del modo degradado.
 * <p>
 * Ojo: aqui solo entra la llamada al proveedor. La validacion de la respuesta (barrera 3) ocurre despues, fuera de estos
 * operadores, asi que sus fallos nunca cuentan como fallos del circuit breaker.
 */
@Component
public class ProtectorLlm {

	private static final Logger log = LoggerFactory.getLogger(ProtectorLlm.class);

	private final CircuitBreakerRegistry circuitos;
	private final RetryRegistry reintentos;
	private final TimeLimiterRegistry limites;
	private final BulkheadRegistry mamparas;
	private final MetricasRagPort metricas;

	ProtectorLlm(CircuitBreakerRegistry circuitos, RetryRegistry reintentos, TimeLimiterRegistry limites,
			BulkheadRegistry mamparas, MetricasRagPort metricas) {
		this.circuitos = circuitos;
		this.reintentos = reintentos;
		this.limites = limites;
		this.mamparas = mamparas;
		this.metricas = metricas;
	}

	/** @param operacion {@code chat} o {@code embedding}: elige las instancias {@code llm-chat} / {@code llm-embedding}. */
	public <T> Mono<T> ejecutar(String operacion, Callable<T> llamada) {
		String instancia = "llm-" + operacion;
		return Mono.fromCallable(() -> medir(operacion, llamada))
				.subscribeOn(Schedulers.boundedElastic())   // Spring AI y Jedis son bloqueantes
				.transformDeferred(BulkheadOperator.of(mamparas.bulkhead(instancia)))
				.transformDeferred(TimeLimiterOperator.of(limites.timeLimiter(instancia)))
				.transformDeferred(CircuitBreakerOperator.of(circuitos.circuitBreaker(instancia)))
				.transformDeferred(RetryOperator.of(reintentos.retry(instancia)))
				.onErrorMap(e -> !(e instanceof AsistenteNoDisponibleException), e -> noDisponible(operacion, e));
	}

	/** Mide cada intento al proveedor: timer asistente.llm.latencia{operacion, resultado}. */
	private <T> T medir(String operacion, Callable<T> llamada) throws Exception {
		long inicio = System.nanoTime();
		try {
			T resultado = llamada.call();
			metricas.latenciaLlm(operacion, "ok", Duration.ofNanos(System.nanoTime() - inicio));
			return resultado;
		}
		catch (Exception e) {
			metricas.latenciaLlm(operacion, "error", Duration.ofNanos(System.nanoTime() - inicio));
			throw e;
		}
	}

	private AsistenteNoDisponibleException noDisponible(String operacion, Throwable error) {
		String motivo;
		if (error instanceof CallNotPermittedException) {
			motivo = "circuito abierto";
		}
		else if (error instanceof TimeoutException) {
			motivo = "timeout";
		}
		else if (error instanceof BulkheadFullException) {
			motivo = "demasiadas llamadas concurrentes";
		}
		else {
			motivo = error.getClass().getSimpleName();
			// un 4xx o un fallo inesperado no se arregla reintentando: es un problema de configuracion que hay que ver
			log.error("Fallo del proveedor de IA ({}): {}", operacion, error.toString());
		}
		log.warn("Proveedor de IA no disponible ({}): {}", operacion, motivo);
		return new AsistenteNoDisponibleException(operacion, motivo, error);
	}

}
