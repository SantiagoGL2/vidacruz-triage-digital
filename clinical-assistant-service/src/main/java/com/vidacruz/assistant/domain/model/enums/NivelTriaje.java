package com.vidacruz.assistant.domain.model.enums;

/**
 * Nivel de triaje tal como llega en los eventos de triage-service (campo {@code nivel}). Es una copia deliberada
 * del vocabulario del evento, no una dependencia del dominio de triage (ver ADR 0003).
 */
public enum NivelTriaje {

	CRITICO(1),
	URGENTE(2),
	URGENTE_2(3),
	NO_URGENTE(4),
	NO_URGENTE_2(5);

	private final int numero;

	NivelTriaje(int numero) {
		this.numero = numero;
	}

	public int numero() {
		return numero;
	}

}
