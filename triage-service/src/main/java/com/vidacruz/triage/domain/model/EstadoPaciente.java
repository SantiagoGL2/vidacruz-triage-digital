package com.vidacruz.triage.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** Estados clinicos (RF-02). Transiciones validas: REGISTRADO -> EN_CONSULTA | ALTA, EN_CONSULTA -> ALTA. */
public enum EstadoPaciente {

	REGISTRADO,
	EN_CONSULTA,
	ALTA;

	public Set<EstadoPaciente> destinosValidos() {
		return switch (this) {
			case REGISTRADO -> EnumSet.of(EN_CONSULTA, ALTA);
			case EN_CONSULTA -> EnumSet.of(ALTA);
			case ALTA -> EnumSet.noneOf(EstadoPaciente.class);
		};
	}

	public boolean puedePasarA(EstadoPaciente destino) {
		return destinosValidos().contains(destino);
	}

}
