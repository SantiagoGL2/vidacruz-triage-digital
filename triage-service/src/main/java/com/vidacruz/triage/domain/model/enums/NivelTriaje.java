package com.vidacruz.triage.domain.model.enums;

/** Nivel de triaje (RN-01) con su SLA por defecto (RN-02). El numero menor es mayor gravedad. */
public enum NivelTriaje {

	CRITICO(1, 5),
	URGENTE(2, 10),
	URGENTE_2(3, 30),
	NO_URGENTE(4, 60),
	NO_URGENTE_2(5, 120);

	private final int numero;
	private final int slaPorDefectoMinutos;

	NivelTriaje(int numero, int slaPorDefectoMinutos) {
		this.numero = numero;
		this.slaPorDefectoMinutos = slaPorDefectoMinutos;
	}

	public int numero() {
		return numero;
	}

	/** SLA del enunciado (RN-02) en minutos: unica fuente de verdad; la configuracion solo lo sobrescribe. */
	public int slaPorDefectoMinutos() {
		return slaPorDefectoMinutos;
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
