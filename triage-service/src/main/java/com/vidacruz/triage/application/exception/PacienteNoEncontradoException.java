package com.vidacruz.triage.application.exception;

import java.util.UUID;

/** El paciente solicitado no existe (la capa web la traduce a 404). */
public class PacienteNoEncontradoException extends RuntimeException {

	private final UUID id;

	public PacienteNoEncontradoException(UUID id) {
		super("No existe un paciente con id " + id);
		this.id = id;
	}

	public UUID id() {
		return id;
	}

}
