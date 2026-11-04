package com.vidacruz.assistant.application.port.in;

import com.vidacruz.assistant.application.result.EstadoUrgenciasConsultado;
import reactor.core.publisher.Mono;

public interface ConsultarEstadoUrgenciasUseCase {

	Mono<EstadoUrgenciasConsultado> consultar();

}
