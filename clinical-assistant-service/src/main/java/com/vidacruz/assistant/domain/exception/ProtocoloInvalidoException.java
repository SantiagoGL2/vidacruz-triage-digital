package com.vidacruz.assistant.domain.exception;

/** Un archivo del corpus no tiene el formato esperado (metadatos o secciones). Impide indexar: no se indexa a medias. */
public class ProtocoloInvalidoException extends RuntimeException {

	public ProtocoloInvalidoException(String mensaje) {
		super(mensaje);
	}

}
