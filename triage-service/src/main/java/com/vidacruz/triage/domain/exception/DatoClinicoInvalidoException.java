package com.vidacruz.triage.domain.exception;

import java.util.List;
import java.util.stream.Collectors;

/** Uno o varios datos clinicos fuera de rango. La capa web la traducira a 400 con detalle por campo. */
public class DatoClinicoInvalidoException extends RuntimeException {

	private final List<CampoInvalido> errores;

	public DatoClinicoInvalidoException(List<CampoInvalido> errores) {
		super(errores.stream().map(CampoInvalido::mensaje).collect(Collectors.joining("; ")));
		this.errores = List.copyOf(errores);
	}

	public List<CampoInvalido> errores() {
		return errores;
	}

}
