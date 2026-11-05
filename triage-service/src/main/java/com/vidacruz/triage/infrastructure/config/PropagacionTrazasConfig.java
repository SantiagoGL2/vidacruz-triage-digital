package com.vidacruz.triage.infrastructure.config;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Propagacion de contexto de traza en formato W3C ({@code traceparent}, {@code tracestate}, {@code baggage}).
 * Spring Boot normalmente la configura solo, pero en este arranque la autoconfiguracion dejaba un propagador vacio
 * ({@code NoopTextMapPropagator}): sin cabeceras {@code traceparent} ni en las peticiones HTTP salientes ni en los mensajes
 * de Kafka, y la traza se cortaba entre servicios. Declararlo aqui lo hace explicito y verificable (ver
 * {@code TrazaDistribuidaTests}); Boot se retira al encontrar este bean.
 */
@Configuration
class PropagacionTrazasConfig {

	@Bean
	ContextPropagators propagadoresW3c() {
		return ContextPropagators.create(
				TextMapPropagator.composite(W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance()));
	}

}
