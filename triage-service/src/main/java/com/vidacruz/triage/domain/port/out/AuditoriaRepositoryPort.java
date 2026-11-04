package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.CambioClinico;
import reactor.core.publisher.Mono;

/** Auditoria append-only (RN-04): a proposito solo expone insercion. */
public interface AuditoriaRepositoryPort {

	Mono<Void> registrar(CambioClinico cambio);

}
