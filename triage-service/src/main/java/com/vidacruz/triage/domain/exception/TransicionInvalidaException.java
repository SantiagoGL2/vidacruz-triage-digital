package com.vidacruz.triage.domain.exception;

import com.vidacruz.triage.domain.model.enums.EstadoPaciente;

/** Transicion de estado no permitida (RF-02). La capa web la traducira a 409. */
public class TransicionInvalidaException extends RuntimeException {

	private final EstadoPaciente desde;
	private final EstadoPaciente hacia;

	public TransicionInvalidaException(EstadoPaciente desde, EstadoPaciente hacia) {
		super(desde == hacia
				? "El paciente ya se encuentra en estado %s".formatted(desde)
				: "No se puede pasar de %s a %s. Destinos validos desde %s: %s"
						.formatted(desde, hacia, desde, desde.destinosValidos().isEmpty() ? "ninguno" : desde.destinosValidos()));
		this.desde = desde;
		this.hacia = hacia;
	}

	public EstadoPaciente desde() {
		return desde;
	}

	public EstadoPaciente hacia() {
		return hacia;
	}

}
