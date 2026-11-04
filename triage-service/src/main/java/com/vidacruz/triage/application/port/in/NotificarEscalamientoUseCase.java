package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.command.NotificarEscalamientoComando;
import reactor.core.publisher.Mono;

public interface NotificarEscalamientoUseCase {

	/** Emite la alerta del escalamiento por todos los canales, una sola vez por eventId. */
	Mono<Void> notificar(NotificarEscalamientoComando comando);

}
