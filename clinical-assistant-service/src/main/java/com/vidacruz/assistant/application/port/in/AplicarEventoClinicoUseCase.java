package com.vidacruz.assistant.application.port.in;

import com.vidacruz.assistant.application.command.AplicarEventoComando;
import com.vidacruz.assistant.application.result.ResultadoProcesamiento;
import reactor.core.publisher.Mono;

/** Aplica un evento de triage a la vista de urgencias (RF-04 parte B). */
public interface AplicarEventoClinicoUseCase {

	Mono<ResultadoProcesamiento> aplicar(AplicarEventoComando comando);

}
