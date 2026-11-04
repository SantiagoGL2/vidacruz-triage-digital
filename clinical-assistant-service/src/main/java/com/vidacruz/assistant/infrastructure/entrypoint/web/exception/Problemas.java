package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import com.vidacruz.assistant.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Fabrica del formato unico de error (RFC 7807): type, title, status, detail, instance + correlationId/timestamp. Toma el
 * status y el type del catalogo {@link ErrorApi} y los textos de {@code messages.properties} (MessageSource), en el idioma de
 * la peticion (Accept-Language) con el espanol como idioma por defecto.
 */
@Component
public class Problemas {

	/** Un campo invalido: nombre, valor recibido y por que se rechazo. */
	public record ErrorCampo(String campo, Object valor, String mensaje) {
	}

	private final MessageSource mensajes;

	Problemas(MessageSource mensajes) {
		this.mensajes = mensajes;
	}

	/** Error del catalogo con su mensaje ({@code error.<codigo>.mensaje}) y los argumentos dados. */
	public ProblemDetail crear(ErrorApi error, ServerWebExchange exchange, Object... argumentos) {
		return construir(error.status(), error.tipo(), texto(error.claveTitulo(), exchange),
				texto(error.claveMensaje(), exchange, argumentos), exchange);
	}

	/** 400 de validacion con la lista de campos invalidos. */
	public ProblemDetail validacion(List<ErrorCampo> errores, String campos, ServerWebExchange exchange) {
		ProblemDetail problema = crear(ErrorApi.VALIDACION, exchange, String.valueOf(errores.size()), campos);
		problema.setProperty(PropiedadesProblema.ERRORES, errores);
		return problema;
	}

	/** Errores HTTP genericos del framework: conservan su status; type {@code <URI_BASE>http-<status>}. */
	public ProblemDetail http(HttpStatusCode status, String titulo, String detalle, ServerWebExchange exchange) {
		return construir(status, URI.create(ErrorApi.URI_BASE + "http-" + status.value()), titulo, detalle, exchange);
	}

	/** Texto localizado (clave de messages.properties) en el idioma de la peticion. */
	public String texto(String clave, ServerWebExchange exchange, Object... argumentos) {
		Locale locale = exchange.getLocaleContext().getLocale();
		return mensajes.getMessage(clave, argumentos, locale != null ? locale : Locale.getDefault());
	}

	private static ProblemDetail construir(HttpStatusCode status, URI tipo, String titulo, String detalle,
			ServerWebExchange exchange) {
		ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
		problema.setType(tipo);
		problema.setTitle(titulo);
		problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problema.setProperty(PropiedadesProblema.CORRELATION_ID, CorrelationIdWebFilter.de(exchange));
		problema.setProperty(PropiedadesProblema.TIMESTAMP, Instant.now());
		return problema;
	}

}
