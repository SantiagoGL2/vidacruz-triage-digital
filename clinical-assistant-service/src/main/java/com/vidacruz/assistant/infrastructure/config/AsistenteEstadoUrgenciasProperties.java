package com.vidacruz.assistant.infrastructure.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * assistant.estado-urgencias.*: prefijo de las claves en Redis (para no chocar con el vector store que comparte la
 * instancia), TTL de las claves de deduplicacion por eventId y zona horaria con la que se expresan las fechas.
 */
@ConfigurationProperties("assistant.estado-urgencias")
public record AsistenteEstadoUrgenciasProperties(String keyPrefix, Duration ttlDeduplicacion, ZoneId zonaHoraria) {
}
