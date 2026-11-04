package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import com.vidacruz.assistant.domain.exception.EstadoUrgenciasNoDisponibleException;
import com.vidacruz.assistant.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Traduce excepciones a ProblemDetail (RFC 7807) con cuerpo estable. Al cliente nunca se le entrega un stacktrace
 * ni el mensaje de una excepcion inesperada: eso solo va al log, con el correlationId.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

	/** Redis no responde: modo degradado explicito, no un 500. */
	@ExceptionHandler(EstadoUrgenciasNoDisponibleException.class)
	ResponseEntity<ProblemDetail> estadoNoDisponible(EstadoUrgenciasNoDisponibleException ex, ServerWebExchange exchange) {
		log.warn("Vista de urgencias no disponible correlationId={}: {}", CorrelationIdWebFilter.de(exchange),
				ex.getMessage());
		ProblemDetail problema = Problemas.crear(HttpStatus.SERVICE_UNAVAILABLE, "estado-no-disponible",
				"Estado de urgencias no disponible",
				"La vista de urgencias no esta disponible temporalmente (el almacen Redis no responde). "
						+ "Reintenta en unos segundos.", exchange);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "5").body(problema);
	}

	/** 404 de rutas inexistentes, 405, 415...: conservan su status pero con el formato comun. */
	@ExceptionHandler(ResponseStatusException.class)
	ProblemDetail errorHttp(ResponseStatusException ex, ServerWebExchange exchange) {
		HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
		String titulo = status != null ? status.getReasonPhrase() : "Error";
		return Problemas.crear(ex.getStatusCode(), "http-" + ex.getStatusCode().value(), titulo,
				ex.getReason() != null ? ex.getReason() : titulo, exchange);
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail inesperado(Exception ex, ServerWebExchange exchange) {
		log.error("Error no controlado en {} {} correlationId={}", exchange.getRequest().getMethod(),
				exchange.getRequest().getPath(), CorrelationIdWebFilter.de(exchange), ex);
		return Problemas.crear(HttpStatus.INTERNAL_SERVER_ERROR, "interno", "Error interno",
				"Ocurrio un error interno. Reporta el correlationId al equipo de soporte.", exchange);
	}

}
