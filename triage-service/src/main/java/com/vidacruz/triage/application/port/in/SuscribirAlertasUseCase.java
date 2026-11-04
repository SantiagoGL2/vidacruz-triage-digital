package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.domain.model.Alerta;
import reactor.core.publisher.Flux;

public interface SuscribirAlertasUseCase {

	/** Alertas en vivo: cada suscriptor recibe las emitidas desde que se conecta. */
	Flux<Alerta> suscribir();

}
