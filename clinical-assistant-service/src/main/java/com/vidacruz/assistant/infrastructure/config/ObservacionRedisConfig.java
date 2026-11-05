package com.vidacruz.assistant.infrastructure.config;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cada comando de Redis del cliente reactivo (Lettuce) pasa a ser un span hijo de quien lo ejecuta (por ejemplo, el
 * consumo de un evento de Kafka). Spring Boot no lo activa por si solo. El vector store del RAG usa Jedis y no se traza.
 */
@Configuration
class ObservacionRedisConfig {

	@Bean
	ClientResourcesBuilderCustomizer trazasLettuce(ObservationRegistry registro) {
		return recursos -> recursos.tracing(new MicrometerTracing(registro, "redis"));
	}

}
