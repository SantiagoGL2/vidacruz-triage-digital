package com.vidacruz.assistant.application.port.in;

import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import reactor.core.publisher.Mono;

/** Carga el corpus y deja el indice vectorial al dia (idempotente). */
public interface IndexarProtocolosUseCase {

	Mono<ResultadoSincronizacion> indexar();

	/** Indexacion deshabilitada por configuracion: se asume que el indice ya existe y se marca listo. */
	void asumirIndiceExistente();

}
