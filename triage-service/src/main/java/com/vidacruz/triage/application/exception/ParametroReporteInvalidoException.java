package com.vidacruz.triage.application.exception;

/** Un parametro del reporte no es valido (rango invertido o demasiado grande). La capa web la traduce a 400. */
public class ParametroReporteInvalidoException extends RuntimeException {

	private final String parametro;
	private final transient Object valor;

	public ParametroReporteInvalidoException(String parametro, Object valor, String motivo) {
		super(motivo);
		this.parametro = parametro;
		this.valor = valor;
	}

	public String parametro() {
		return parametro;
	}

	public Object valor() {
		return valor;
	}

}
