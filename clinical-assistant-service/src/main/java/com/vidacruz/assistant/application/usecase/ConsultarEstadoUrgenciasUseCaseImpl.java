package com.vidacruz.assistant.application.usecase;

import com.vidacruz.assistant.application.port.in.ConsultarEstadoUrgenciasUseCase;
import com.vidacruz.assistant.application.result.EstadoUrgenciasConsultado;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import java.time.Clock;
import reactor.core.publisher.Mono;

/** Lee la vista materializada; nunca consulta a triage-service. */
public class ConsultarEstadoUrgenciasUseCaseImpl implements ConsultarEstadoUrgenciasUseCase {

	private final EstadoUrgenciasPort estado;
	private final MetricasPort metricas;
	private final Clock clock;

	public ConsultarEstadoUrgenciasUseCaseImpl(EstadoUrgenciasPort estado, MetricasPort metricas, Clock clock) {
		this.estado = estado;
		this.metricas = metricas;
		this.clock = clock;
	}

	@Override
	public Mono<EstadoUrgenciasConsultado> consultar() {
		return estado.consultar()
				.doOnNext(metricas::vistaActualizada)
				.map(vista -> new EstadoUrgenciasConsultado(vista, clock.instant()));
	}

}
