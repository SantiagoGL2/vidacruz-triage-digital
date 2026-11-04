package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.vidacruz.assistant.domain.exception.AsistenteNoDisponibleException;
import com.vidacruz.assistant.domain.exception.EstadoUrgenciasNoDisponibleException;
import com.vidacruz.assistant.domain.exception.IndiceNoDisponibleException;
import com.vidacruz.assistant.infrastructure.config.AsistenteRagProperties;
import com.vidacruz.assistant.infrastructure.entrypoint.web.exception.Problemas.ErrorCampo;
import com.vidacruz.assistant.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Traduce excepciones a ProblemDetail (RFC 7807) con cuerpo estable. Al cliente nunca se le entrega un stacktrace
 * ni el mensaje de una excepcion inesperada: eso solo va al log, con el correlationId.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

	/** Mensaje del modo degradado de la consulta (proveedor de IA caido). */
	static final String MENSAJE_DEGRADADO =
			"El asistente clínico no está disponible temporalmente; consulte los protocolos impresos";

	static final String MENSAJE_INDICE =
			"El asistente clínico se está preparando (indexando los protocolos); reintente en unos segundos. "
					+ "Mientras tanto, consulte los protocolos impresos";

	private final AsistenteRagProperties rag;

	GlobalExceptionHandler(AsistenteRagProperties rag) {
		this.rag = rag;
	}

	// ------------------------------------------------------------------ modo degradado (503)

	/** El proveedor de IA (chat o embeddings) no responde: circuito abierto, timeout, reintentos agotados... */
	@ExceptionHandler(AsistenteNoDisponibleException.class)
	ResponseEntity<ProblemDetail> proveedorNoDisponible(AsistenteNoDisponibleException ex, ServerWebExchange exchange) {
		ProblemDetail problema = Problemas.crear(HttpStatus.SERVICE_UNAVAILABLE, "asistente-no-disponible",
				"Asistente clínico no disponible", MENSAJE_DEGRADADO, exchange);
		problema.setProperty("operacion", ex.operacion());
		return degradado(problema, rag.retryAfter().proveedor().toSeconds());
	}

	/** El indice de protocolos aun no esta listo (primera indexacion en curso o fallando). */
	@ExceptionHandler(IndiceNoDisponibleException.class)
	ResponseEntity<ProblemDetail> indiceNoDisponible(IndiceNoDisponibleException ex, ServerWebExchange exchange) {
		ProblemDetail problema = Problemas.crear(HttpStatus.SERVICE_UNAVAILABLE, "indice-no-disponible",
				"Índice de protocolos no disponible", MENSAJE_INDICE, exchange);
		return degradado(problema, rag.retryAfter().indice().toSeconds());
	}

	/** Redis no responde: modo degradado explicito, no un 500. */
	@ExceptionHandler(EstadoUrgenciasNoDisponibleException.class)
	ResponseEntity<ProblemDetail> estadoNoDisponible(EstadoUrgenciasNoDisponibleException ex, ServerWebExchange exchange) {
		log.warn("Vista de urgencias no disponible correlationId={}: {}", CorrelationIdWebFilter.de(exchange),
				ex.getMessage());
		ProblemDetail problema = Problemas.crear(HttpStatus.SERVICE_UNAVAILABLE, "estado-no-disponible",
				"Estado de urgencias no disponible",
				"La vista de urgencias no esta disponible temporalmente (el almacen Redis no responde). "
						+ "Reintenta en unos segundos.", exchange);
		return degradado(problema, 5);
	}

	private static ResponseEntity<ProblemDetail> degradado(ProblemDetail problema, long reintentarEnSegundos) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(reintentarEnSegundos)).body(problema);
	}

	// ------------------------------------------------------------------ entrada invalida (400)

	/** Bean Validation del DTO. */
	@ExceptionHandler(WebExchangeBindException.class)
	ProblemDetail dtoInvalido(WebExchangeBindException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.getFieldErrors().stream()
				.map(e -> new ErrorCampo(e.getField(), e.getRejectedValue(),
						e.getDefaultMessage() != null ? e.getDefaultMessage() : "valor invalido"))
				.toList();
		return validacion(errores, exchange);
	}

	/** JSON malformado, tipos invalidos o cuerpo ausente. */
	@ExceptionHandler(ServerWebInputException.class)
	ProblemDetail entradaInvalida(ServerWebInputException ex, ServerWebExchange exchange) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof JsonMappingException mapping) {
				String campo = mapping.getPath().stream()
						.map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
						.collect(Collectors.joining("."));
				return validacion(List.of(new ErrorCampo(campo.isEmpty() ? "(cuerpo)" : campo, null,
						"tipo o valor invalido")), exchange);
			}
			if (t instanceof JsonProcessingException) {
				return Problemas.crear(HttpStatus.BAD_REQUEST, "json-malformado", "JSON malformado",
						"El cuerpo no es un JSON valido", exchange);
			}
		}
		return Problemas.crear(HttpStatus.BAD_REQUEST, "solicitud-invalida", "Solicitud invalida",
				"La solicitud no se pudo interpretar (cuerpo ausente o ilegible)", exchange);
	}

	private static ProblemDetail validacion(List<ErrorCampo> errores, ServerWebExchange exchange) {
		String campos = errores.stream().map(ErrorCampo::campo).distinct().collect(Collectors.joining(", "));
		return Problemas.validacion(errores, "Hay %d dato(s) invalido(s): %s".formatted(errores.size(), campos), exchange);
	}

	// ------------------------------------------------------------------ resto

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
