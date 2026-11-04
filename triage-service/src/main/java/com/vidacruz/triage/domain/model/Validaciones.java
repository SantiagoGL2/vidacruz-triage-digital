package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import java.util.List;

/** Helpers de validacion de rangos que acumulan errores (no fallan al primero). */
final class Validaciones {

	private Validaciones() {
	}

	static void entero(List<CampoInvalido> errores, String campo, int valor, int min, int max, String unidad) {
		if (valor < min || valor > max) {
			errores.add(new CampoInvalido(campo, valor, "%d-%d%s".formatted(min, max, unidad)));
		}
	}

	static void decimal(List<CampoInvalido> errores, String campo, double valor, double min, double max, String unidad) {
		if (Double.isNaN(valor) || valor < min || valor > max) {
			errores.add(new CampoInvalido(campo, valor, "%.1f-%.1f%s".formatted(min, max, unidad)));
		}
	}

}
