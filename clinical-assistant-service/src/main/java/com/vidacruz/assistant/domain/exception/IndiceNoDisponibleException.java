package com.vidacruz.assistant.domain.exception;

import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;

/** El indice de protocolos aun no esta listo (indexacion en curso o fallando): no se puede consultar. La API responde 503. */
public class IndiceNoDisponibleException extends RuntimeException {

	private final EstadoIndexacion estado;

	public IndiceNoDisponibleException(EstadoIndexacion estado, String detalleError) {
		super("El indice de protocolos no esta listo (" + estado + ")" + (detalleError != null ? ": " + detalleError : ""));
		this.estado = estado;
	}

	/** Fase en la que estaba el indice; decide el mensaje que ve el usuario. */
	public EstadoIndexacion estado() {
		return estado;
	}

}
