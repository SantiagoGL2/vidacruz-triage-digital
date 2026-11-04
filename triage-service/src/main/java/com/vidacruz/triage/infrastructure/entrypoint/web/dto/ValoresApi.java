package com.vidacruz.triage.infrastructure.entrypoint.web.dto;

/**
 * Valores de los enums de dominio (niveles y estados) para las anotaciones de OpenAPI, que exigen constantes de compilacion
 * y no admiten {@code NivelTriaje.CRITICO.name()}. Es la unica copia en codigo de produccion de esos nombres como texto;
 * {@code ValoresApiTests} comprueba que coinciden con los enums, asi que no pueden desincronizarse en silencio.
 */
public final class ValoresApi {

	public static final String CRITICO = "CRITICO";
	public static final String URGENTE = "URGENTE";
	public static final String URGENTE_2 = "URGENTE_2";
	public static final String NO_URGENTE = "NO_URGENTE";
	public static final String NO_URGENTE_2 = "NO_URGENTE_2";

	public static final String REGISTRADO = "REGISTRADO";
	public static final String EN_CONSULTA = "EN_CONSULTA";
	public static final String ALTA = "ALTA";

	private ValoresApi() {
	}

}
