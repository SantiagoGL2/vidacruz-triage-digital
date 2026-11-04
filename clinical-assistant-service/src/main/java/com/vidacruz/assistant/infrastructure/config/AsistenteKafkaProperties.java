package com.vidacruz.assistant.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * assistant.kafka.*: topic de eventos de triage, su DLT, el consumer group propio y los reintentos del consumidor
 * (backoff exponencial para errores transitorios, p. ej. Redis caido).
 */
@ConfigurationProperties("assistant.kafka")
public record AsistenteKafkaProperties(
		boolean habilitado,
		String topic,
		String dltTopic,
		String groupId,
		Duration timeoutProcesamiento,
		Retry retry) {

	public record Retry(Duration initialInterval, double multiplier, Duration maxInterval, Duration maxElapsedTime) {
	}

}
