package com.vidacruz.triage.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** triage.alertas.*: alertas de escalamiento (RF-06): consumidor Kafka, canal SSE y canal webhook. */
@ConfigurationProperties("triage.alertas")
public record TriageAlertasProperties(Consumidor consumidor, Sse sse, Webhook webhook) {

	public record Consumidor(
			boolean habilitado,
			String groupId,
			int reintentos,
			Duration timeoutProcesamiento,
			Duration backoffInicial,
			Duration backoffMaximo,
			int capacidadDeduplicacion) {
	}

	public record Sse(Duration heartbeat) {
	}

	/** {@code url} vacia = canal deshabilitado. */
	public record Webhook(
			String url,
			Duration timeout,
			int intentos,
			Duration esperaReintento,
			int ventanaCircuitBreaker,
			float umbralFallos,
			Duration esperaCircuitoAbierto) {

		public boolean habilitado() {
			return url != null && !url.isBlank();
		}

	}

}
