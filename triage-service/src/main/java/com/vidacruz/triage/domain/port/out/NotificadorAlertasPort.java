package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.Alerta;
import reactor.core.publisher.Mono;

/** Canal de salida de las alertas (SSE, webhook...). Cada canal falla de forma independiente de los demas. */
public interface NotificadorAlertasPort {

	Mono<Void> notificar(Alerta alerta);

}
