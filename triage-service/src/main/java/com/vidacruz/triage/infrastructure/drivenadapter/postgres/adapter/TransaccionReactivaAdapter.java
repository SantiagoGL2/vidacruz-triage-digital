package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.port.out.TransaccionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Transaccion reactiva sobre el R2dbcTransactionManager: commit al completar, rollback ante cualquier error. */
@Component
class TransaccionReactivaAdapter implements TransaccionPort {

	private final TransactionalOperator operador;

	TransaccionReactivaAdapter(ReactiveTransactionManager transactionManager) {
		this.operador = TransactionalOperator.create(transactionManager);
	}

	@Override
	public <T> Mono<T> enTransaccion(Mono<T> operacion) {
		return operador.transactional(operacion);
	}

}
