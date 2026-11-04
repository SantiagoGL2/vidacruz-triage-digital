package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.context.ContextoCorrelacion;
import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import reactor.core.publisher.Mono;

/** Paso comun de los casos de uso: dejar auditoria (RN-04) y evento en el outbox para un cambio clinico. */
class RegistroCambioClinico {

	private final AuditoriaRepositoryPort auditoria;
	private final OutboxRepositoryPort outbox;
	private final FabricaEventos fabricaEventos;

	RegistroCambioClinico(AuditoriaRepositoryPort auditoria, OutboxRepositoryPort outbox, FabricaEventos fabricaEventos) {
		this.auditoria = auditoria;
		this.outbox = outbox;
		this.fabricaEventos = fabricaEventos;
	}

	Mono<Void> registrar(CambioClinico cambio) {
		// defer: no se invoca ningun puerto hasta que el paso anterior (p. ej. el UPDATE) haya completado bien.
		return Mono.defer(() -> auditoria.registrar(cambio))
				.then(ContextoCorrelacion.actual())
				.flatMap(correlationId -> outbox.guardar(fabricaEventos.desde(cambio, correlationId)));
	}

}
