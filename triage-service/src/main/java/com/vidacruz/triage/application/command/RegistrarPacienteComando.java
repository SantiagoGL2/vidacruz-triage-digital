package com.vidacruz.triage.application.command;

/** Datos de entrada del RF-01. Las reglas de rango las valida el dominio. */
public record RegistrarPacienteComando(
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
