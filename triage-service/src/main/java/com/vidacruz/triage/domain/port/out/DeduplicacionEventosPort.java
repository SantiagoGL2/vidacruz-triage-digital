package com.vidacruz.triage.domain.port.out;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** Idempotencia del consumidor: recuerda los eventId ya procesados. */
public interface DeduplicacionEventosPort {

	/** true si es la primera vez que se ve el evento (y lo recuerda); false si ya se habia procesado. */
	Mono<Boolean> registrarSiNuevo(UUID eventId);

}
