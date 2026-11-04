package com.vidacruz.assistant.infrastructure.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationEntryPointFailureHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Autenticacion por API key (header X-API-Key) sin sesion. Solo las probes de salud (las necesitan
 * Docker/Kubernetes) y la documentacion OpenAPI son publicas; el resto exige key valida. Los 401 salen como ProblemDetail.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, AsistenteSecurityProperties propiedades,
			ObjectMapper objectMapper) {
		var entryPoint = ApiKeyAuthentication.entryPoint(objectMapper, propiedades.apiKeyHeader());

		var filtro = new AuthenticationWebFilter(ApiKeyAuthentication.manager(propiedades.apiKey()));
		filtro.setServerAuthenticationConverter(ApiKeyAuthentication.converter(propiedades.apiKeyHeader()));
		filtro.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
		filtro.setAuthenticationFailureHandler(new ServerAuthenticationEntryPointFailureHandler(entryPoint));

		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.logout(ServerHttpSecurity.LogoutSpec::disable)
				.requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
				.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
				.addFilterAt(filtro, SecurityWebFiltersOrder.AUTHENTICATION)
				.authorizeExchange(exchanges -> exchanges
						.pathMatchers("/actuator/health/**").permitAll()
						// Documentacion OpenAPI / Swagger UI: publica (su habilitacion se controla con springdoc.*.enabled)
						.pathMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**").permitAll()
						.anyExchange().authenticated())
				.build();
	}

}
