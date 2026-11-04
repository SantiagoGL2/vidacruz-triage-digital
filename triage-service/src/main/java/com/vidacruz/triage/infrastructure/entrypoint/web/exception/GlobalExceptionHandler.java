package com.vidacruz.triage.infrastructure.entrypoint.web.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.application.exception.ParametroReporteInvalidoException;
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
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Traduce excepciones a ProblemDetail (RFC 7807) con cuerpo estable. Los errores salen del catalogo {@link ErrorApi} y sus
 * textos de messages.properties. Al cliente nunca se le entrega un stacktrace ni el mensaje de una excepcion inesperada:
 * eso solo va al log, con el correlationId.
 * <p>
 * Los mensajes por campo que genera este handler tambien son claves de messages.properties; los que ya vienen redactados por
 * una excepcion de dominio/aplicacion (rango esperado de un dato clinico, rango invalido del reporte) se pasan tal cual.
 */
@Slf4j
@RestControllerAdvice
class GlobalExceptionHandler {

	private final Problemas problemas;

	GlobalExceptionHandler(Problemas problemas) {
		this.problemas = problemas;
	}

	@ExceptionHandler(DatoClinicoInvalidoException.class)
	ProblemDetail datoClinicoInvalido(DatoClinicoInvalidoException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.errores().stream()
				.map(e -> new ErrorCampo(e.campo(), e.valorRecibido(),
						problemas.texto("campo.valor-invalido", exchange, e.rangoEsperado())))
				.toList();
		return validacion(errores, exchange);
	}

	/** Bean Validation del DTO (campos obligatorios). */
	@ExceptionHandler(WebExchangeBindException.class)
	ProblemDetail dtoInvalido(WebExchangeBindException ex, ServerWebExchange exchange) {
		List<ErrorCampo> errores = ex.getFieldErrors().stream()
				.map(e -> new ErrorCampo(e.getField(), e.getRejectedValue(), mensajeBeanValidation(e.getCode(), exchange)))
				.toList();
		return validacion(errores, exchange);
	}

	/** Parametro de query/ruta obligatorio ausente: se nombra el parametro. */
	@ExceptionHandler(MissingRequestValueException.class)
	ProblemDetail parametroAusente(MissingRequestValueException ex, ServerWebExchange exchange) {
		return validacion(List.of(new ErrorCampo(ex.getName(), null, problemas.texto("campo.parametro-obligatorio", exchange))),
				exchange);
	}

	/** Rango del reporte invalido (desde >= hasta, o mas grande que el maximo configurado). */
	@ExceptionHandler(ParametroReporteInvalidoException.class)
	ProblemDetail parametroReporteInvalido(ParametroReporteInvalidoException ex, ServerWebExchange exchange) {
		Object valor = ex.valor() == null ? null : ex.valor().toString();
		return validacion(List.of(new ErrorCampo(ex.parametro(), valor, ex.getMessage())), exchange);
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
					? tipoLegible(formato.getTargetType(), exchange) : problemas.texto("tipo.generico", exchange);
			return validacion(List.of(new ErrorCampo(campo.isEmpty() ? problemas.texto("campo.cuerpo", exchange) : campo, valor,
					problemas.texto("campo.tipo-invalido", exchange, esperado))), exchange);
		}
		if (causa instanceof JsonProcessingException) {
			return problemas.crear(ErrorApi.JSON_MALFORMADO, exchange);
		}
		if (causa instanceof TypeMismatchException mismatch && ex.getMethodParameter() != null) {
			return validacion(List.of(new ErrorCampo(ex.getMethodParameter().getParameterName(), mismatch.getValue(),
					problemas.texto("campo.formato-invalido", exchange, tipoLegible(mismatch.getRequiredType(), exchange)))),
					exchange);
		}
		return problemas.crear(ErrorApi.SOLICITUD_INVALIDA, exchange);
	}

	@ExceptionHandler(PacienteNoEncontradoException.class)
	ProblemDetail noEncontrado(PacienteNoEncontradoException ex, ServerWebExchange exchange) {
		return problemas.crear(ErrorApi.NO_ENCONTRADO, exchange, ex.id());
	}

	@ExceptionHandler(TransicionInvalidaException.class)
	ProblemDetail transicionInvalida(TransicionInvalidaException ex, ServerWebExchange exchange) {
		ProblemDetail problema;
		if (ex.desde() == ex.hacia()) {
			problema = problemas.crearConMensaje(ErrorApi.TRANSICION_INVALIDA, exchange, "error.transicion-invalida.mensaje-mismo-estado",
					ex.desde());
		}
		else {
			String destinos = ex.desde().destinosValidos().isEmpty() ? problemas.texto("transicion.sin-destinos", exchange)
					: ex.desde().destinosValidos().toString();
			problema = problemas.crear(ErrorApi.TRANSICION_INVALIDA, exchange, ex.desde(), ex.hacia(), destinos);
		}
		problema.setProperty(PropiedadesProblema.ESTADO_ACTUAL, ex.desde().name());
		problema.setProperty(PropiedadesProblema.ESTADO_SOLICITADO, ex.hacia().name());
		return problema;
	}

	@ExceptionHandler(ConflictoConcurrenciaException.class)
	ProblemDetail conflictoConcurrencia(ConflictoConcurrenciaException ex, ServerWebExchange exchange) {
		return problemas.crear(ErrorApi.CONFLICTO_CONCURRENCIA, exchange, ex.id());
	}

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

	private static Throwable primeraCausa(Throwable ex, Class<? extends Throwable> tipo) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (tipo.isInstance(t)) {
				return t;
			}
		}
		return null;
	}

	private ProblemDetail validacion(List<ErrorCampo> errores, ServerWebExchange exchange) {
		String campos = errores.stream().map(ErrorCampo::campo).distinct().collect(Collectors.joining(", "));
		return problemas.validacion(errores, campos, exchange);
	}

	private String mensajeBeanValidation(String codigo, ServerWebExchange exchange) {
		return "NotNull".equals(codigo) ? problemas.texto("campo.obligatorio", exchange)
				: problemas.texto("campo.invalido", exchange, codigo);
	}

	private String tipoLegible(Class<?> tipo, ServerWebExchange exchange) {
		if (tipo == null) {
			return problemas.texto("tipo.generico", exchange);
		}
		if (java.time.temporal.TemporalAccessor.class.isAssignableFrom(tipo)) {
			return problemas.texto("tipo.fecha-iso", exchange);
		}
		if (tipo.isEnum()) {
			return problemas.texto("tipo.enum", exchange, java.util.Arrays.toString(tipo.getEnumConstants()));
		}
		return tipo.getSimpleName();
	}

}
