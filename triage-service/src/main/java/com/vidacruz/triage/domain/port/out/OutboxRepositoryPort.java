package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.event.EventoTriaje;
import reactor.core.publisher.Mono;

/** Escribe el evento en outbox_eventos; la publicacion a Kafka la hace otro componente. */
public interface OutboxRepositoryPort {

	Mono<Void> guardar(EventoTriaje evento);

}
