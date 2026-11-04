package com.vidacruz.triage.infrastructure.entrypoint.web.exception;

import com.vidacruz.triage.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

/** Fabrica del formato unico de error (RFC 7807): type, title, status, detail, instance + correlationId/timestamp. */
final class Problemas {

	private Problemas() {
	}

	/** Un campo invalido: nombre, valor recibido y por que se rechazo. */
	record ErrorCampo(String campo, Object valor, String mensaje) {
	}

	static ProblemDetail crear(HttpStatusCode status, String codigo, String titulo, String detalle,
			ServerWebExchange exchange) {
		ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
		problema.setType(URI.create("urn:vidacruz:triage:problema:" + codigo));
		problema.setTitle(titulo);
		problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problema.setProperty("correlationId", CorrelationIdWebFilter.de(exchange));
		problema.setProperty("timestamp", Instant.now());
		return problema;
	}

	static ProblemDetail validacion(List<ErrorCampo> errores, String detalle, ServerWebExchange exchange) {
		ProblemDetail problema = crear(HttpStatus.BAD_REQUEST, "validacion", "Datos invalidos", detalle, exchange);
		problema.setProperty("errores", errores);
		return problema;
	}

}
