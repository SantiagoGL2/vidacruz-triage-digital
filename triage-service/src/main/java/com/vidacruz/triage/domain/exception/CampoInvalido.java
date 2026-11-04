package com.vidacruz.triage.domain.exception;

/** Un campo que no cumple la validacion: nombre, valor recibido y rango/regla esperada. */
public record CampoInvalido(String campo, Object valorRecibido, String rangoEsperado) {

	public String mensaje() {
		return "%s=%s invalido (esperado: %s)".formatted(campo, valorRecibido, rangoEsperado);
	}

}
