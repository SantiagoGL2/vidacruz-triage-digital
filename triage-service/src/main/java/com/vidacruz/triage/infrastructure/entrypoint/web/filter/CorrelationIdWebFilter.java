package com.vidacruz.triage.infrastructure.entrypoint.web.filter;

import com.vidacruz.triage.application.context.ContextoCorrelacion;
import com.vidacruz.triage.infrastructure.entrypoint.web.CabecerasHttp;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Lee X-Correlation-Id (o genera uno), lo devuelve en la respuesta y lo pone en el Context de Reactor.
 * Corre antes que Spring Security para que tambien los 401 lleven correlationId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

	/** Atributo del exchange, para quien necesite el id fuera del Context (handlers de error, entry point). */
	public static final String ATRIBUTO = CorrelationIdWebFilter.class.getName() + ".id";

	/** Solo caracteres seguros: el valor termina en logs y cabeceras (evita inyeccion de lineas). */
	private static final Pattern VALIDO = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String recibido = exchange.getRequest().getHeaders().getFirst(CabecerasHttp.CORRELATION_ID);
		String id = recibido != null && VALIDO.matcher(recibido).matches() ? recibido : UUID.randomUUID().toString();
		exchange.getAttributes().put(ATRIBUTO, id);
		exchange.getResponse().getHeaders().set(CabecerasHttp.CORRELATION_ID, id);
		return chain.filter(exchange).contextWrite(ctx -> ctx.put(ContextoCorrelacion.CLAVE, id));
	}

	public static String de(ServerWebExchange exchange) {
		return exchange.getAttributeOrDefault(ATRIBUTO, ContextoCorrelacion.SIN_CORRELACION);
	}

}
