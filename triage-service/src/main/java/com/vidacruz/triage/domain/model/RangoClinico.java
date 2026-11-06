package com.vidacruz.triage.domain.model;

import static com.vidacruz.triage.domain.model.RangosClinicos.*;

/**
 * Rango valido (inclusivo) de un dato clinico de entrada: nombre del campo en la API, minimo, maximo y unidad. Los limites
 * vienen de {@link RangosClinicos}, la unica fuente de verdad (la misma que lee la documentacion OpenAPI). La unidad va
 * sin tildes porque forma parte del mensaje de error.
 */
public enum RangoClinico {

	FRECUENCIA_CARDIACA("frecuenciaCardiaca", FRECUENCIA_CARDIACA_MIN, FRECUENCIA_CARDIACA_MAX, "bpm"),
	PRESION_SISTOLICA("presionSistolica", PRESION_SISTOLICA_MIN, PRESION_SISTOLICA_MAX, "mmHg"),
	PRESION_DIASTOLICA("presionDiastolica", PRESION_DIASTOLICA_MIN, PRESION_DIASTOLICA_MAX, "mmHg"),
	SPO2("spo2", SPO2_MIN, SPO2_MAX, "%"),
	TEMPERATURA("temperatura", TEMPERATURA_MIN, TEMPERATURA_MAX, "C"),
	DOLOR("dolor", DOLOR_MIN, DOLOR_MAX, ""),
	GCS("gcs", GCS_MIN, GCS_MAX, ""),
	EDAD("edad", EDAD_MIN, EDAD_MAX, "anios"),
	/** Longitud del nombre completo (tras recortar espacios). */
	NOMBRE("nombreCompleto", NOMBRE_MIN, NOMBRE_MAX, "caracteres");

	private final String campo;
	private final double minimo;
	private final double maximo;
	private final String unidad;
	private final boolean decimal;

	RangoClinico(String campo, int minimo, int maximo, String unidad) {
		this(campo, minimo, maximo, unidad, false);
	}

	RangoClinico(String campo, double minimo, double maximo, String unidad) {
		this(campo, minimo, maximo, unidad, true);
	}

	RangoClinico(String campo, double minimo, double maximo, String unidad, boolean decimal) {
		this.campo = campo;
		this.minimo = minimo;
		this.maximo = maximo;
		this.unidad = unidad;
		this.decimal = decimal;
	}

	public String campo() {
		return campo;
	}

	public double minimo() {
		return minimo;
	}

	public double maximo() {
		return maximo;
	}

	public String unidad() {
		return unidad;
	}

	/** {@code true} si el valor esta dentro del rango (inclusivo); un NaN nunca lo esta. */
	public boolean contiene(double valor) {
		return !Double.isNaN(valor) && valor >= minimo && valor <= maximo;
	}

	/** Texto del rango para el mensaje de error: «20-250 bpm», «34.0-42.0 C», «0-10». */
	public String descripcion() {
		String limites = decimal ? "%.1f-%.1f".formatted(minimo, maximo) : "%d-%d".formatted((int) minimo, (int) maximo);
		return unidad.isEmpty() ? limites : limites + " " + unidad;
	}

}
