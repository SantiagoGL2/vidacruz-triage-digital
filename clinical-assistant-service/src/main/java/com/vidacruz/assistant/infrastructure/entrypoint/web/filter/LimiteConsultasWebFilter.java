package com.vidacruz.assistant.infrastructure.entrypoint.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.infrastructure.config.AsistenteRateLimitProperties;
import com.vidacruz.assistant.infrastructure.config.security.AsistenteSecurityProperties;
import com.vidacruz.assistant.infrastructure.entrypoint.web.exception.ErrorApi;
import com.vidacruz.assistant.infrastructure.entrypoint.web.exception.Problemas;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Limita {@code POST /asistente/consulta} por API key con un RateLimiter de Resilience4j: cada consulta cuesta una llamada al
 * proveedor de IA, asi que un cliente no puede agotarlo ni disparar la factura (docs/asistente-rag.md).
 * <p>
 * Corre DESPUES de la autenticacion de Spring Security (un cliente sin clave valida recibe 401, no consume cupo ni 429) y
 * rechaza sin esperar ({@code timeoutDuration = 0}: nunca bloquea un hilo del event loop). Al superar el limite responde 429
 * con el formato de error comun ({@link ErrorApi#DEMASIADAS_PETICIONES}) y {@code Retry-After}. La clave nunca se usa en
 * claro: el limiter se identifica con un hash. Solo hay un limiter por clave valida (las invalidas no llegan hasta aqui).
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class LimiteConsultasWebFilter implements WebFilter {

	static final String RUTA = "/asistente/consulta";

	/** Consultas rechazadas por superar el limite (docs/observabilidad.md). Va aqui y no en NombresMetricas: entrypoint no conoce drivenadapter. */
	static final String METRICA_RECHAZOS = "asistente.consultas.limitadas";

	private final AsistenteRateLimitProperties propiedades;
	private final AsistenteSecurityProperties seguridad;
	private final Problemas problemas;
	private final ObjectMapper objectMapper;
	private final MeterRegistry metricas;
	private final RateLimiterRegistry limitadores;

	LimiteConsultasWebFilter(AsistenteRateLimitProperties propiedades, AsistenteSecurityProperties seguridad, Problemas problemas,
			ObjectMapper objectMapper, MeterRegistry metricas) {
		this.propiedades = propiedades;
		this.seguridad = seguridad;
		this.problemas = problemas;
		this.objectMapper = objectMapper;
		this.metricas = metricas;
		this.limitadores = RateLimiterRegistry.of(RateLimiterConfig.custom()
				.limitForPeriod(propiedades.peticiones())
				.limitRefreshPeriod(propiedades.periodo())
				.timeoutDuration(Duration.ZERO)       // no esperar: rechazar al instante
				.build());
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (!propiedades.habilitado() || !esConsulta(exchange)) {
			return chain.filter(exchange);
		}
		String clave = exchange.getRequest().getHeaders().getFirst(seguridad.apiKeyHeader());
		RateLimiter limitador = limitadores.rateLimiter("consulta-" + huella(clave));
		if (limitador.acquirePermission()) {
			return chain.filter(exchange);
		}
		metricas.counter(METRICA_RECHAZOS).increment();
		log.warn("Consulta rechazada por limite de peticiones ({} cada {}) cliente={}", propiedades.peticiones(), propiedades.periodo(),
				huella(clave));
		return escribir429(exchange);
	}

	private static boolean esConsulta(ServerWebExchange exchange) {
		return HttpMethod.POST.equals(exchange.getRequest().getMethod()) && RUTA.equals(exchange.getRequest().getPath().value());
	}

	private Mono<Void> escribir429(ServerWebExchange exchange) {
		long reintentar = propiedades.reintentarEnSegundos();
		ProblemDetail problema = problemas.crear(ErrorApi.DEMASIADAS_PETICIONES, exchange, propiedades.peticiones(),
				propiedades.periodo().toSeconds(), reintentar);
		var respuesta = exchange.getResponse();
		respuesta.setStatusCode(ErrorApi.DEMASIADAS_PETICIONES.status());
		respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		respuesta.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(reintentar));
		return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(problema))
				.map(bytes -> (DataBuffer) respuesta.bufferFactory().wrap(bytes))
				.flatMap(buffer -> respuesta.writeWith(Mono.just(buffer)));
	}

	/** Identificador estable y no reversible de la clave (primeros 12 hex de su SHA-256) para nombrar el limiter y los logs. */
	private static String huella(String clave) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest((clave == null ? "" : clave).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 6);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
