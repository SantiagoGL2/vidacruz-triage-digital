package com.vidacruz.triage.infrastructure.entrypoint.web.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.exception.TransicionInvalidaException;
import com.vidacruz.triage.infrastructure.entrypoint.web.exception.Problemas.ErrorCampo;
import com.vidacruz.triage.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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

	@ExceptionHandler(DatoClinicoInvalidoException.class)
	ProblemDetail datoClinicoInvalido(DatoClinicoInvalidoException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.errores().stream()
				.map(e -> new ErrorCampo(e.campo(), e.valorRecibido(), "valor invalido; esperado: " + e.rangoEsperado()))
				.toList();
		return validacion(errores, exchange);
	}

	/** Bean Validation del DTO (campos obligatorios). */
	@ExceptionHandler(WebExchangeBindException.class)
	ProblemDetail dtoInvalido(WebExchangeBindException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.getFieldErrors().stream()
				.map(e -> new ErrorCampo(e.getField(), e.getRejectedValue(), mensajeBeanValidation(e.getCode())))
				.toList();
		return validacion(errores, exchange);
	}

	/** JSON malformado, tipos invalidos, enum desconocido, UUID invalido, cuerpo ausente. */
	@ExceptionHandler(ServerWebInputException.class)
	ProblemDetail entradaInvalida(ServerWebInputException ex, ServerWebExchange exchange) {
		// Se busca en toda la cadena de causas: el mas profundo no siempre es la excepcion de Jackson.
		Throwable causa = primeraCausa(ex, JsonProcessingException.class);
		if (causa == null) {
			causa = primeraCausa(ex, TypeMismatchException.class);
		}
		if (causa instanceof JsonMappingException mapping) {
			String campo = mapping.getPath().stream()
					.map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
					.collect(Collectors.joining("."));
			Object valor = causa instanceof InvalidFormatException formato ? formato.getValue() : null;
			String esperado = causa instanceof InvalidFormatException formato && formato.getTargetType() != null
					? tipoLegible(formato.getTargetType()) : "tipo valido";
			return validacion(List.of(new ErrorCampo(campo.isEmpty() ? "(cuerpo)" : campo, valor,
					"tipo o valor invalido; esperado: " + esperado)), exchange);
		}
		if (causa instanceof JsonProcessingException) {
			return Problemas.crear(HttpStatus.BAD_REQUEST, "json-malformado", "JSON malformado",
					"El cuerpo no es un JSON valido", exchange);
		}
		if (causa instanceof TypeMismatchException mismatch && ex.getMethodParameter() != null) {
			return validacion(List.of(new ErrorCampo(ex.getMethodParameter().getParameterName(), mismatch.getValue(),
					"formato invalido; esperado: " + tipoLegible(mismatch.getRequiredType()))), exchange);
		}
		return Problemas.crear(HttpStatus.BAD_REQUEST, "solicitud-invalida", "Solicitud invalida",
				"La solicitud no se pudo interpretar (cuerpo ausente o ilegible)", exchange);
	}

	@ExceptionHandler(PacienteNoEncontradoException.class)
	ProblemDetail noEncontrado(PacienteNoEncontradoException ex, ServerWebExchange exchange) {
		return Problemas.crear(HttpStatus.NOT_FOUND, "no-encontrado", "Paciente no encontrado", ex.getMessage(), exchange);
	}

	@ExceptionHandler(TransicionInvalidaException.class)
	ProblemDetail transicionInvalida(TransicionInvalidaException ex, ServerWebExchange exchange) {
		ProblemDetail problema = Problemas.crear(HttpStatus.CONFLICT, "transicion-invalida", "Transicion de estado invalida",
				ex.getMessage(), exchange);
		problema.setProperty("estadoActual", ex.desde().name());
		problema.setProperty("estadoSolicitado", ex.hacia().name());
		return problema;
	}

	@ExceptionHandler(ConflictoConcurrenciaException.class)
	ProblemDetail conflictoConcurrencia(ConflictoConcurrenciaException ex, ServerWebExchange exchange) {
		return Problemas.crear(HttpStatus.CONFLICT, "conflicto-concurrencia", "Conflicto de concurrencia", ex.getMessage(),
				exchange);
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

	private static Throwable primeraCausa(Throwable ex, Class<? extends Throwable> tipo) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (tipo.isInstance(t)) {
				return t;
			}
		}
		return null;
	}

	private static ProblemDetail validacion(List<ErrorCampo> errores, ServerWebExchange exchange) {
		String campos = errores.stream().map(ErrorCampo::campo).distinct().collect(Collectors.joining(", "));
		return Problemas.validacion(errores, "Hay %d dato(s) invalido(s): %s".formatted(errores.size(), campos), exchange);
	}

	private static String mensajeBeanValidation(String codigo) {
		return "NotNull".equals(codigo) ? "campo obligatorio" : "valor invalido (" + codigo + ")";
	}

	private static String tipoLegible(Class<?> tipo) {
		if (tipo == null) {
			return "tipo valido";
		}
		if (tipo.isEnum()) {
			return "uno de " + java.util.Arrays.toString(tipo.getEnumConstants());
		}
		return tipo.getSimpleName();
	}

}
