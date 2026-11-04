package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.EstadoIndice;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;

/** Estado (en memoria, por replica) de la indexacion de protocolos: lo consultan la API y el indicador de salud. */
public interface EstadoIndicePort {

	/** Foto consistente del estado actual. */
	EstadoIndice actual();

	void marcarListo(ResultadoSincronizacion resultado);

	void marcarFallo(String error);

}
