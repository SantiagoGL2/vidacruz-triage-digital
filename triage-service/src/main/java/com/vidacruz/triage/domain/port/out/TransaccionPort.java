package com.vidacruz.triage.domain.port.out;

import reactor.core.publisher.Mono;

/** Ejecuta un flujo dentro de una unica transaccion reactiva (commit al completar, rollback ante error). */
public interface TransaccionPort {

	<T> Mono<T> enTransaccion(Mono<T> operacion);

}
