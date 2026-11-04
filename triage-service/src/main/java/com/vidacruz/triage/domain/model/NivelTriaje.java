package com.vidacruz.triage.domain.model;

/** Nivel de triaje (RN-01). El numero menor es mayor gravedad. */
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

	public static NivelTriaje desdeNumero(int numero) {
		for (NivelTriaje nivel : values()) {
			if (nivel.numero == numero) {
				return nivel;
			}
		}
		throw new IllegalArgumentException("Nivel de triaje inexistente: " + numero);
	}

}
