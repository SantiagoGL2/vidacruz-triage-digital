package com.vidacruz.assistant.infrastructure.config;

import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Comprueba al arrancar el presupuesto de tiempo de las llamadas al proveedor de IA: el read-timeout del cliente HTTP
 * ({@code spring.http.client.read-timeout}, {@code LLM_HTTP_READ_TIMEOUT}) debe ser MENOR que el TimeLimiter de cada
 * instancia ({@code LLM_TIMEOUT}).
 * <p>
 * Si el TimeLimiter cortara primero, la llamada bloqueante seguiria ocupando un hilo de {@code boundedElastic} hasta que
 * venciera el timeout HTTP, con el cupo del Bulkhead ya liberado: el limite de concurrencia dejaria de ser real. Con el
 * read-timeout menor, el hilo termina solo y el TimeLimiter queda como red de seguridad. Si no se cumple, la aplicacion
 * NO arranca (mejor un error claro al desplegar que un agotamiento de hilos en produccion).
 */
@Component
class PresupuestoTiempoLlmValidador {

	private static final Logger log = LoggerFactory.getLogger(PresupuestoTiempoLlmValidador.class);

	private static final String PROPIEDAD_READ_TIMEOUT = "spring.http.client.read-timeout";
	private static final List<String> INSTANCIAS = List.of("llm-chat", "llm-embedding");

	PresupuestoTiempoLlmValidador(Environment entorno, TimeLimiterRegistry limites) {
		Duration lectura = entorno.getProperty(PROPIEDAD_READ_TIMEOUT, Duration.class);
		for (String instancia : INSTANCIAS) {
			validar(lectura, limites.timeLimiter(instancia).getTimeLimiterConfig().getTimeoutDuration());
		}
		log.info("Presupuesto de tiempo del proveedor de IA: read-timeout HTTP {} < TimeLimiter", lectura);
	}

	/** @throws IllegalStateException si no hay read-timeout o no es estrictamente menor que el TimeLimiter. */
	static void validar(Duration lectura, Duration limite) {
		if (lectura == null || lectura.isZero() || lectura.isNegative()) {
			throw new IllegalStateException("Presupuesto de tiempo del LLM invalido: falta " + PROPIEDAD_READ_TIMEOUT
					+ " (LLM_HTTP_READ_TIMEOUT); sin el, una llamada colgada ocuparia un hilo indefinidamente.");
		}
		if (lectura.compareTo(limite) >= 0) {
			throw new IllegalStateException("Presupuesto de tiempo del LLM invalido: el read-timeout HTTP (" + lectura
					+ ", LLM_HTTP_READ_TIMEOUT) debe ser MENOR que el TimeLimiter (" + limite + ", LLM_TIMEOUT). Si no, al "
					+ "cortar el TimeLimiter la llamada bloqueante seguiria ocupando un hilo con el cupo del Bulkhead ya liberado.");
		}
	}

}
