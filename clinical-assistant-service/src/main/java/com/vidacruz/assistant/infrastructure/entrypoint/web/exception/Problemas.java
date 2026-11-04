package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import com.vidacruz.assistant.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

/** Fabrica del formato unico de error (RFC 7807): type, title, status, detail, instance + correlationId/timestamp. */
final class Problemas {

	private Problemas() {
	}

	static ProblemDetail crear(HttpStatusCode status, String codigo, String titulo, String detalle,
			ServerWebExchange exchange) {
		ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalle);
		problema.setType(URI.create("urn:vidacruz:asistente:problema:" + codigo));
		problema.setTitle(titulo);
		problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problema.setProperty("correlationId", CorrelationIdWebFilter.de(exchange));
		problema.setProperty("timestamp", Instant.now());
		return problema;
	}

}
