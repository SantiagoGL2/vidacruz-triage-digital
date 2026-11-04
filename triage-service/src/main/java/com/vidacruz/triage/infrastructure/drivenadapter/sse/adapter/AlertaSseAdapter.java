package com.vidacruz.triage.infrastructure.drivenadapter.sse.adapter;

import com.vidacruz.triage.domain.model.Alerta;
import com.vidacruz.triage.domain.port.out.FlujoAlertasPort;
import com.vidacruz.triage.domain.port.out.NotificadorAlertasPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Canal SSE: un sink multicast en memoria reparte cada alerta a todos los clientes conectados.
 * {@code directBestEffort} (y no onBackpressureBuffer): no acumula alertas mientras nadie escucha, asi un cliente que
 * se conecta no recibe alertas viejas, y un cliente lento no frena a los demas (se le descartan). Es el canal de
 * tiempo real; el historial vive en el outbox/auditoria.
 */
@Component
class AlertaSseAdapter implements NotificadorAlertasPort, FlujoAlertasPort {

	private final Sinks.Many<Alerta> sink = Sinks.many().multicast().directBestEffort();
	private final MeterRegistry registry;

	AlertaSseAdapter(MeterRegistry registry) {
		this.registry = registry;
	}

	@Override
	public Mono<Void> notificar(Alerta alerta) {
		return Mono.fromRunnable(() -> {
			// busyLooping: varios hilos del consumidor pueden emitir a la vez y el sink exige emision serializada
			sink.emitNext(alerta, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
			registry.counter("triage.alertas.emitidas", "canal", "sse").increment();
		});
	}

	@Override
	public Flux<Alerta> alertas() {
		return sink.asFlux();
	}

}
