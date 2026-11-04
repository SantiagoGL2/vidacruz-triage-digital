package com.vidacruz.triage.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** triage.outbox.*: publicador del outbox hacia Kafka. */
@ConfigurationProperties("triage.outbox")
public record TriageOutboxProperties(
		boolean habilitado,
		Duration intervalo,
		int batchSize,
		int maxIntentos,
		Duration backoffInicial,
		Duration backoffMaximo,
		Duration timeoutPublicacion) {
}
