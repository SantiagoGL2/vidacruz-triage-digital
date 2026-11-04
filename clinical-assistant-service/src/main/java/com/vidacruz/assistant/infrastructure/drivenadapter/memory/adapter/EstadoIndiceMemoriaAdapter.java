package com.vidacruz.assistant.infrastructure.drivenadapter.memory.adapter;

import com.vidacruz.assistant.domain.model.EstadoIndice;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Estado de la indexacion de protocolos en memoria (cada replica indexa y lo sabe por su cuenta). Se guarda como un unico
 * valor inmutable: cada cambio lo reemplaza entero.
 */
@Component
class EstadoIndiceMemoriaAdapter implements EstadoIndicePort {

	private final AtomicReference<EstadoIndice> estado = new AtomicReference<>(EstadoIndice.indexando());
	private final Clock reloj;

	EstadoIndiceMemoriaAdapter(Clock reloj) {
		this.reloj = reloj;
	}

	@Override
	public EstadoIndice actual() {
		return estado.get();
	}

	@Override
	public void marcarListo(ResultadoSincronizacion resultado) {
		estado.set(EstadoIndice.listo(resultado, reloj.instant()));
	}

	@Override
	public void marcarFallo(String error) {
		estado.set(EstadoIndice.fallido(error, reloj.instant()));
	}

}
