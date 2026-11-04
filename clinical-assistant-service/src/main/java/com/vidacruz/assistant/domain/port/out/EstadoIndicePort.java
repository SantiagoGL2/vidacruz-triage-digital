package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;

/** Estado (en memoria, por replica) de la indexacion de protocolos: lo consultan la API y el indicador de salud. */
public interface EstadoIndicePort {

	boolean listo();

	void marcarListo(ResultadoSincronizacion resultado);

	void marcarFallo(String motivo);

	/** Para el indicador de salud: fragmentos indexados (0 si no esta listo). */
	int fragmentosIndexados();

	/** Para el indicador de salud: por que no esta listo (indexando, o el ultimo error). */
	String motivo();

}
