package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.result.ResultadoPublicacion;
import reactor.core.publisher.Mono;

public interface PublicarEventosPendientesUseCase {

	/** Un ciclo del publicador: lee un lote del outbox, lo publica y marca lo publicado. No falla si Kafka esta caido. */
	Mono<ResultadoPublicacion> publicarPendientes();

}
