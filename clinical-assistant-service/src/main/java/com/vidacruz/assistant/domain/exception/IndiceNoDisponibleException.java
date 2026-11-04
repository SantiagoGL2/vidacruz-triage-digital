package com.vidacruz.assistant.domain.exception;

/** El indice de protocolos aun no esta listo (indexacion en curso o fallando): no se puede consultar. La API responde 503. */
public class IndiceNoDisponibleException extends RuntimeException {

	public IndiceNoDisponibleException(String motivo) {
		super("El indice de protocolos no esta listo: " + motivo);
	}

}
