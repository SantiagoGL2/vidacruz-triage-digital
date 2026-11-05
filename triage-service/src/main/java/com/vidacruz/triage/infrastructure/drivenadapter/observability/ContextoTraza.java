package com.vidacruz.triage.infrastructure.drivenadapter.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.util.context.ContextView;

/**
 * Puente entre el outbox (asincrono) y la traza distribuida. Al guardar el evento se captura el {@code traceparent} de
 * la peticion en curso; al publicarlo, otro hilo y otro momento despues, se restaura como padre de un span propio, de modo
 * que la publicacion a Kafka (y el consumo posterior en el asistente) queden en la misma traza que la peticion HTTP.
 */
@Component
public class ContextoTraza {

	/** Nombre de la cabecera W3C; la usa tanto la columna del outbox como la cabecera Kafka que genera la observacion. */
	static final String TRACEPARENT = "traceparent";

	private final Tracer tracer;
	private final Propagator propagator;

	ContextoTraza(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/**
	 * {@code traceparent} de la peticion en curso, o {@code null} si no hay traza activa (p. ej. un proceso de fondo). El span
	 * se busca primero en el contexto de Reactor (donde WebFlux deja la observacion de la peticion; el hilo que ejecuta un
	 * operador no siempre tiene restauradas las variables de hilo) y, si no esta, en el span actual del hilo.
	 */
	public String capturar(ContextView contexto) {
		Span actual = null;
		Observation observacion = contexto.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
		if (observacion != null) {
			TracingObservationHandler.TracingContext trazado = observacion.getContextView()
					.getOrDefault(TracingObservationHandler.TracingContext.class, null);
			actual = trazado != null ? trazado.getSpan() : null;
		}
		if (actual == null) {
			actual = tracer.currentSpan();
		}
		if (actual == null) {
			return null;
		}
		// Formato W3C: version-traceId-spanId-flags. Se arma directamente: Propagator.inject del puente OpenTelemetry lee el
		// contexto del hilo (que aqui no tiene el span) y devolveria el portador vacio.
		TraceContext contextoTraza = actual.context();
		return "00-" + contextoTraza.traceId() + "-" + contextoTraza.spanId() + "-" + (Boolean.TRUE.equals(contextoTraza.sampled()) ? "01" : "00");
	}

	/**
	 * Ejecuta {@code operacion} (que debe iniciar el envio de forma sincrona y devolver su futuro) dentro de un span
	 * {@code nombre} hijo del contexto guardado. El span termina cuando el futuro se completa. Sin {@code traceparent} la
	 * operacion se ejecuta tal cual.
	 */
	public <T> CompletableFuture<T> enContexto(String traceparent, String nombre, Supplier<CompletableFuture<T>> operacion) {
		if (traceparent == null || traceparent.isBlank()) {
			return operacion.get();
		}
		Map<String, String> portador = Map.of(TRACEPARENT, traceparent);
		Span span = propagator.extract(portador, Map::get).name(nombre).start();
		try (Tracer.SpanInScope ignorado = tracer.withSpan(span)) {
			return operacion.get().whenComplete((resultado, error) -> {
				if (error != null) {
					span.error(error);
				}
				span.end();
			});
		}
		catch (RuntimeException e) {
			span.error(e);
			span.end();
			throw e;
		}
	}

}
