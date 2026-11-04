package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.event.EventoPendiente;
import reactor.core.publisher.Mono;

/** Publica un evento en el bus (Kafka). Completa solo cuando el broker confirma; falla si no puede entregarlo. */
public interface PublicadorEventosPort {

	Mono<Void> publicar(EventoPendiente evento);

}
