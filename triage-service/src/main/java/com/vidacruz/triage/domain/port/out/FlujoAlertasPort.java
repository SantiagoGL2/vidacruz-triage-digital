package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.Alerta;
import reactor.core.publisher.Flux;

/** Flujo en vivo de alertas para los clientes conectados (cada suscriptor ve solo las alertas posteriores a su conexion). */
public interface FlujoAlertasPort {

	Flux<Alerta> alertas();

}
