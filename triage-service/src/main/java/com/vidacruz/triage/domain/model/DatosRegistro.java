package com.vidacruz.triage.domain.model;

/**
 * Datos crudos de un registro (RF-01), tal como llegan: sin validar ni clasificar. Quien valida, construye los
 * {@link SignosVitales} y clasifica es {@code RegistradorPacientes}.
 */
public record DatosRegistro(
		String nombreCompleto,
		int edad,
		int frecuenciaCardiaca,
		int presionSistolica,
		int presionDiastolica,
		int spo2,
		double temperatura,
		int dolor,
		int gcs,
		String quejaPrincipal) {
}
