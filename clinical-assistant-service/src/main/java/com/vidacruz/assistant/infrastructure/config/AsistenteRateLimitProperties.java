package com.vidacruz.assistant.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * assistant.rate-limit.*: limite de consultas al asistente por API key (cada consulta tiene costo en el proveedor de IA,
 * ver docs/asistente-rag.md). Se permiten {@code peticiones} consultas cada {@code periodo}; al superarlo, 429.
 */
@ConfigurationProperties("assistant.rate-limit")
public record AsistenteRateLimitProperties(boolean habilitado, int peticiones, Duration periodo) {

	public AsistenteRateLimitProperties {
		if (peticiones < 1) {
			throw new IllegalArgumentException("assistant.rate-limit.peticiones debe ser >= 1");
		}
		if (periodo == null || periodo.isZero() || periodo.isNegative()) {
			throw new IllegalArgumentException("assistant.rate-limit.periodo debe ser una duracion positiva");
		}
	}

	/** Segundos que se sugieren esperar (Retry-After): el periodo completo es una cota segura de cuando se repone el cupo. */
	public long reintentarEnSegundos() {
		return Math.max(1, periodo.plusMillis(999).toSeconds());
	}

}
