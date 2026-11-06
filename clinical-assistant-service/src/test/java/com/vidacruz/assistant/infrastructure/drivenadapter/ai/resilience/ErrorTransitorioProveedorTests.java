package com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

/** Solo se reintenta lo transitorio: conexion, 5xx y 429 (nunca un timeout de respuesta). Nunca 4xx de configuracion ni errores de validacion. */
class ErrorTransitorioProveedorTests {

	private final ErrorTransitorioProveedor predicado = new ErrorTransitorioProveedor();

	@Test
	void reintentaLosErroresDeConexion() {
		assertThat(predicado.test(new ResourceAccessException("I/O error", new SocketTimeoutException("Connect timed out")))).isTrue();
		assertThat(predicado.test(new ResourceAccessException("I/O error", new HttpConnectTimeoutException("HTTP connect timed out")))).isTrue();
		assertThat(predicado.test(new ResourceAccessException("I/O error", new IOException("Connection refused")))).isTrue();
		assertThat(predicado.test(new RuntimeException("envuelto", new IOException("reset")))).isTrue();
	}

	@Test
	void nuncaReintentaUnTimeoutDeRespuesta() {
		assertThat(predicado.test(new TimeoutException())).as("TimeLimiter").isFalse();
		assertThat(predicado.test(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")))).isFalse();
		assertThat(predicado.test(new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out")))).isFalse();
	}

	@Test
	void reintentaLos5xx() {
		assertThat(predicado.test(new TransientAiException("HTTP 500 - Internal Server Error"))).isTrue();
		assertThat(predicado.test(new TransientAiException("HTTP 503 - Service Unavailable"))).isTrue();
	}

	@Test
	void reintentaElTooManyRequests429() {
		assertThat(predicado.test(new NonTransientAiException("HTTP 429 - Rate limit exceeded"))).isTrue();
		assertThat(predicado.test(new NonTransientAiException("429 - sin prefijo"))).isTrue();
		assertThat(predicado.test(new NonTransientAiException("HTTP 4290 - no es un 429"))).isFalse();
	}

	@Test
	void nuncaReintentaLos4xxDeConfiguracion() {
		assertThat(predicado.test(new NonTransientAiException("HTTP 401 - Incorrect API key"))).isFalse();
		assertThat(predicado.test(new NonTransientAiException("HTTP 400 - Bad request"))).isFalse();
		assertThat(predicado.test(new NonTransientAiException("HTTP 404 - The model does not exist"))).isFalse();
	}

	@Test
	void nuncaReintentaErroresDeValidacionNiDeResilienciaNiDesconocidos() {
		assertThat(predicado.test(new IllegalArgumentException("validacion"))).isFalse();
		assertThat(predicado.test(new IllegalStateException())).isFalse();
		assertThat(predicado.test(CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("t")))).isFalse();
		assertThat(predicado.test(BulkheadFullException.createBulkheadFullException(
				io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("t")))).isFalse();
	}

}
