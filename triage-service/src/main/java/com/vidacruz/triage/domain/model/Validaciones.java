package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import java.util.List;

/** Helpers de validacion de rangos que acumulan errores (no fallan al primero). */
final class Validaciones {

	private Validaciones() {
	}

	static void entero(List<CampoInvalido> errores, RangoClinico rango, int valor) {
		if (!rango.contiene(valor)) {
			errores.add(new CampoInvalido(rango.campo(), valor, rango.descripcion()));
		}
	}

	static void decimal(List<CampoInvalido> errores, RangoClinico rango, double valor) {
		if (!rango.contiene(valor)) {
			errores.add(new CampoInvalido(rango.campo(), valor, rango.descripcion()));
		}
	}

}
