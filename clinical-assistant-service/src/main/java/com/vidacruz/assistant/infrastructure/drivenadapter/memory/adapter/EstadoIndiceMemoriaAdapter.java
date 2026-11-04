package com.vidacruz.assistant.infrastructure.drivenadapter.memory.adapter;

import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import org.springframework.stereotype.Component;

/** Estado de la indexacion de protocolos en memoria (cada replica indexa y lo sabe por su cuenta). */
@Component
class EstadoIndiceMemoriaAdapter implements EstadoIndicePort {

	private volatile boolean listo;
	private volatile int fragmentos;
	private volatile String motivo = "indexando los protocolos (aun no termino la primera indexacion)";

	@Override
	public boolean listo() {
		return listo;
	}

	@Override
	public void marcarListo(ResultadoSincronizacion resultado) {
		this.fragmentos = resultado.fragmentosIndexados();
		this.motivo = "listo";
		this.listo = true;
	}

	@Override
	public void marcarFallo(String motivo) {
		this.listo = false;
		this.fragmentos = 0;
		this.motivo = motivo;
	}

	@Override
	public int fragmentosIndexados() {
		return fragmentos;
	}

	@Override
	public String motivo() {
		return motivo;
	}

}
