package com.vidacruz.assistant.infrastructure.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.assistant.infrastructure.entrypoint.web.filter.CorrelationIdWebFilter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Piezas de la autenticacion por API key (header configurable, por defecto X-API-Key). */
final class ApiKeyAuthentication {

	private ApiKeyAuthentication() {
	}

	/** Extrae la key del header; si no viene no autentica (la regla de autorizacion respondera 401). */
	static ServerAuthenticationConverter converter(String header) {
		return exchange -> {
			String key = exchange.getRequest().getHeaders().getFirst(header);
			return key == null || key.isBlank() ? Mono.empty()
					: Mono.just(UsernamePasswordAuthenticationToken.unauthenticated("api-key", key));
		};
	}

	/** Compara en tiempo constante (hash SHA-256 de ambas, para no filtrar la longitud ni el prefijo). */
	static ReactiveAuthenticationManager manager(String keyEsperada) {
		byte[] esperado = sha256(keyEsperada);
		return authentication -> {
			byte[] presentado = sha256(String.valueOf(authentication.getCredentials()));
			if (MessageDigest.isEqual(esperado, presentado)) {
				return Mono.just(UsernamePasswordAuthenticationToken.authenticated("api-client", null,
						List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
			}
			return Mono.error(new BadCredentialsException("API key invalida"));
		};
	}

	/** 401 como ProblemDetail (mismo formato que el resto de errores). */
	static ServerAuthenticationEntryPoint entryPoint(ObjectMapper objectMapper, String header) {
		return (exchange, ex) -> escribir401(objectMapper, header, exchange);
	}

	private static Mono<Void> escribir401(ObjectMapper objectMapper, String header, ServerWebExchange exchange) {
		ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
				"Falta el header %s o su valor no es valido".formatted(header));
		problema.setType(URI.create("urn:vidacruz:asistente:problema:no-autenticado"));
		problema.setTitle("No autenticado");
		problema.setInstance(URI.create(exchange.getRequest().getPath().value()));
		problema.setProperty("correlationId", CorrelationIdWebFilter.de(exchange));
		problema.setProperty("timestamp", Instant.now());
		var respuesta = exchange.getResponse();
		respuesta.setStatusCode(HttpStatus.UNAUTHORIZED);
		respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
		respuesta.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"asistente\"");
		return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(problema))
				.map(bytes -> (DataBuffer) respuesta.bufferFactory().wrap(bytes))
				.flatMap(buffer -> respuesta.writeWith(Mono.just(buffer)));
	}

	private static byte[] sha256(String valor) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
