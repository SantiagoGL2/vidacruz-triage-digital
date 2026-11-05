package com.vidacruz.triage.infrastructure.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Crea y cierra un span (y una observacion de cliente que inyecta {@code traceparent}) al arrancar, en el hilo principal.
 * La primera vez que se usa OpenTelemetry inicializa su almacenamiento de contexto escaneando los jars del classpath con
 * {@code ServiceLoader}: una lectura de disco bloqueante que, de ocurrir en la primera consulta SQL o el primer mensaje,
 * pararia un hilo del event loop (BlockHound lo detecta). Debe correr antes que cualquier otra tarea de arranque que haga
 * consultas, de ahi la maxima prioridad.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CalentamientoTrazas implements ApplicationRunner {

	private final Tracer tracer;
	private final Propagator propagator;
	private final ObservationRegistry observaciones;

	CalentamientoTrazas(Tracer tracer, Propagator propagator, ObservationRegistry observaciones) {
		this.tracer = tracer;
		this.propagator = propagator;
		this.observaciones = observaciones;
	}

	@Override
	public void run(ApplicationArguments args) {
		Span span = tracer.nextSpan().name("calentamiento").start();
		try (Tracer.SpanInScope ignorado = tracer.withSpan(span)) {
			Map<String, String> portador = new HashMap<>();
			propagator.inject(span.context(), portador, Map::put);
			propagator.extract(portador, Map::get).name("calentamiento-hijo").start().end();
			var contexto = new io.micrometer.observation.transport.SenderContext<Map<String, String>>(Map::put,
					io.micrometer.observation.transport.Kind.CLIENT);
			contexto.setCarrier(new HashMap<>());
			Observation.start("calentamiento", () -> contexto, observaciones).stop();
		}
		finally {
			span.end();
		}
	}

}
