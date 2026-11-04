package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.vidacruz.assistant.domain.exception.AsistenteNoDisponibleException;
import com.vidacruz.assistant.domain.exception.EstadoUrgenciasNoDisponibleException;
import com.vidacruz.assistant.domain.exception.IndiceNoDisponibleException;
import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
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
 * Traduce excepciones a ProblemDetail (RFC 7807) con cuerpo estable. Los errores salen del catalogo {@link ErrorApi} y sus
 * textos de messages.properties. Al cliente nunca se le entrega un stacktrace ni el mensaje de una excepcion inesperada:
 * eso solo va al log, con el correlationId.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

	/** Segundos de Retry-After cuando Redis no responde. */
	private static final long REINTENTO_ESTADO_SEGUNDOS = 5;

	/** Variante del 503 del indice cuando la ultima indexacion fallo (la clave por defecto es la de «indexando»). */
	private static final String CLAVE_INDICE_FALLIDO = "error.indice-no-disponible.mensaje-fallido";

	private final AsistenteRagProperties rag;
	private final Problemas problemas;

	GlobalExceptionHandler(AsistenteRagProperties rag, Problemas problemas) {
		this.rag = rag;
		this.problemas = problemas;
	}

	// ------------------------------------------------------------------ modo degradado (503)

	/** El proveedor de IA (chat o embeddings) no responde: circuito abierto, timeout, reintentos agotados... */
	@ExceptionHandler(AsistenteNoDisponibleException.class)
	ResponseEntity<ProblemDetail> proveedorNoDisponible(AsistenteNoDisponibleException ex, ServerWebExchange exchange) {
		ProblemDetail problema = problemas.crear(ErrorApi.ASISTENTE_NO_DISPONIBLE, exchange);
		problema.setProperty(PropiedadesProblema.OPERACION, ex.operacion());
		return degradado(problema, rag.retryAfter().proveedor().toSeconds());
	}

	/** El indice de protocolos aun no esta listo (primera indexacion en curso o fallando). */
	@ExceptionHandler(IndiceNoDisponibleException.class)
	ResponseEntity<ProblemDetail> indiceNoDisponible(IndiceNoDisponibleException ex, ServerWebExchange exchange) {
		String clave = ex.estado() == EstadoIndexacion.FALLIDO ? CLAVE_INDICE_FALLIDO
				: ErrorApi.INDICE_NO_DISPONIBLE.claveMensaje();
		return degradado(problemas.crearConMensaje(ErrorApi.INDICE_NO_DISPONIBLE, exchange, clave),
				rag.retryAfter().indice().toSeconds());
	}

	/** Redis no responde: modo degradado explicito, no un 500. */
	@ExceptionHandler(EstadoUrgenciasNoDisponibleException.class)
	ResponseEntity<ProblemDetail> estadoNoDisponible(EstadoUrgenciasNoDisponibleException ex, ServerWebExchange exchange) {
		log.warn("Vista de urgencias no disponible correlationId={}: {}", CorrelationIdWebFilter.de(exchange),
				ex.getMessage());
		return degradado(problemas.crear(ErrorApi.ESTADO_NO_DISPONIBLE, exchange), REINTENTO_ESTADO_SEGUNDOS);
	}

	private static ResponseEntity<ProblemDetail> degradado(ProblemDetail problema, long reintentarEnSegundos) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(reintentarEnSegundos)).body(problema);
	}

	// ------------------------------------------------------------------ entrada invalida (400)

	/** Bean Validation del DTO. El mensaje de cada campo ya viene resuelto desde messages.properties. */
	@ExceptionHandler(WebExchangeBindException.class)
	ProblemDetail dtoInvalido(WebExchangeBindException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.getFieldErrors().stream()
				.map(e -> new ErrorCampo(e.getField(), e.getRejectedValue(),
						e.getDefaultMessage() != null ? e.getDefaultMessage() : problemas.texto("campo.invalido", exchange, e.getCode())))
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
				return validacion(List.of(new ErrorCampo(campo.isEmpty() ? problemas.texto("campo.cuerpo", exchange) : campo,
						null, problemas.texto("campo.tipo-invalido", exchange))), exchange);
			}
			if (t instanceof JsonProcessingException) {
				return problemas.crear(ErrorApi.JSON_MALFORMADO, exchange);
			}
		}
		return problemas.crear(ErrorApi.SOLICITUD_INVALIDA, exchange);
	}

	private ProblemDetail validacion(List<ErrorCampo> errores, ServerWebExchange exchange) {
		String campos = errores.stream().map(ErrorCampo::campo).distinct().collect(Collectors.joining(", "));
		return problemas.validacion(errores, campos, exchange);
	}

	// ------------------------------------------------------------------ resto

	/** 404 de rutas inexistentes, 405, 415...: conservan su status pero con el formato comun. */
	@ExceptionHandler(ResponseStatusException.class)
	ProblemDetail errorHttp(ResponseStatusException ex, ServerWebExchange exchange) {
		HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
		String titulo = status != null ? status.getReasonPhrase() : problemas.texto("error.http.titulo", exchange);
		return problemas.http(ex.getStatusCode(), titulo, ex.getReason() != null ? ex.getReason() : titulo, exchange);
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail inesperado(Exception ex, ServerWebExchange exchange) {
		log.error("Error no controlado en {} {} correlationId={}", exchange.getRequest().getMethod(),
				exchange.getRequest().getPath(), CorrelationIdWebFilter.de(exchange), ex);
		return problemas.crear(ErrorApi.ERROR_INTERNO, exchange);
	}

}
