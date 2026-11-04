package com.vidacruz.triage.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Resultado de una operacion del agregado: el nuevo estado del {@link Paciente} mas lo necesario para
 * auditoria (RN-04) y para el evento de salida. {@code estadoAnterior} es null solo en el registro.
 */
public record CambioClinico(
		Paciente paciente,
		EstadoPaciente estadoAnterior,
		EstadoPaciente estadoNuevo,
		TipoEventoClinico tipoEvento,
		Instant ocurridoEn,
		String detalle) {

	public CambioClinico {
		Objects.requireNonNull(paciente, "paciente");
		Objects.requireNonNull(estadoNuevo, "estadoNuevo");
		Objects.requireNonNull(tipoEvento, "tipoEvento");
		Objects.requireNonNull(ocurridoEn, "ocurridoEn");
	}

}
