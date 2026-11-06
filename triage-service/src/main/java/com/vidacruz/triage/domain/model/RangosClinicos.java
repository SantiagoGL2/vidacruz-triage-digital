package com.vidacruz.triage.domain.model;

/**
 * Limites de los datos clinicos de entrada (tabla «Validaciones de datos» del enunciado): UNICA fuente de verdad de cada
 * rango. Son constantes de compilacion porque las anotaciones {@code @Schema} de OpenAPI las exigen; las usan
 * {@link RangoClinico} (la validacion) y los DTOs (la documentacion), asi que no pueden desincronizarse.
 * Un limite nuevo o distinto se cambia aqui y en ningun otro sitio.
 */
public final class RangosClinicos {

	public static final int FRECUENCIA_CARDIACA_MIN = 20;
	public static final int FRECUENCIA_CARDIACA_MAX = 250;
	public static final int PRESION_SISTOLICA_MIN = 60;
	public static final int PRESION_SISTOLICA_MAX = 260;
	public static final int PRESION_DIASTOLICA_MIN = 30;
	public static final int PRESION_DIASTOLICA_MAX = 160;
	public static final int SPO2_MIN = 70;
	public static final int SPO2_MAX = 100;
	public static final double TEMPERATURA_MIN = 34.0;
	public static final double TEMPERATURA_MAX = 42.0;
	public static final int DOLOR_MIN = 0;
	public static final int DOLOR_MAX = 10;
	public static final int GCS_MIN = 3;
	public static final int GCS_MAX = 15;
	public static final int EDAD_MIN = 0;
	public static final int EDAD_MAX = 120;
	public static final int NOMBRE_MIN = 3;
	public static final int NOMBRE_MAX = 100;

	private RangosClinicos() {
	}

}
