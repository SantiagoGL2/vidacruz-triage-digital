package com.vidacruz.assistant.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Cadena de seguridad base. Solo las probes de salud son publicas (las necesitan Docker/Kubernetes);
 * todo lo demas exige autenticacion. El WebFilter de API key (X-API-Key) se anade con la funcionalidad.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		return http
				.csrf(ServerHttpSecurity.CsrfSpec::disable)
				.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.authorizeExchange(exchanges -> exchanges
						.pathMatchers("/actuator/health/**").permitAll()
						.anyExchange().authenticated())
				.build();
	}

}
