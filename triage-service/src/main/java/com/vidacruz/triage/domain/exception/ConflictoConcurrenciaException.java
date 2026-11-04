package com.vidacruz.triage.domain.exception;

import java.util.UUID;

/** Otro cambio modifico al paciente entre su lectura y su escritura (optimistic locking). Se traduce a 409. */
public class ConflictoConcurrenciaException extends RuntimeException {

	private final UUID id;

	public ConflictoConcurrenciaException(UUID id) {
		super("El paciente %s fue modificado por otra operacion concurrente; vuelve a consultarlo y reintenta".formatted(id));
		this.id = id;
	}

	public UUID id() {
		return id;
	}

}
