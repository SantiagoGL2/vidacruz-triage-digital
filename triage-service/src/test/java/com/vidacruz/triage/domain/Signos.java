package com.vidacruz.triage.domain;

import com.vidacruz.triage.domain.model.DatosRegistro;
import com.vidacruz.triage.domain.model.SignosVitales;

/** Builder de test: signos vitales NORMALES (nivel 4) que cada test modifica solo en lo que le interesa. */
public final class Signos {

	private int fc = 72;
	private int sistolica = 120;
	private int diastolica = 80;
	private int spo2 = 99;
	private double temperatura = 36.5;
	private int dolor = 2;
	private int gcs = 15;

	private Signos() {
	}

	public static Signos normales() {
		return new Signos();
	}

	public Signos fc(int v) {
		this.fc = v;
		return this;
	}

	public Signos sistolica(int v) {
		this.sistolica = v;
		return this;
	}

	public Signos diastolica(int v) {
		this.diastolica = v;
		return this;
	}

	public Signos spo2(int v) {
		this.spo2 = v;
		return this;
	}

	public Signos temperatura(double v) {
		this.temperatura = v;
		return this;
	}

	public Signos dolor(int v) {
		this.dolor = v;
		return this;
	}

	public Signos gcs(int v) {
		this.gcs = v;
		return this;
	}

	public SignosVitales build() {
		return new SignosVitales(fc, sistolica, diastolica, spo2, temperatura, dolor, gcs);
	}

	/** Datos crudos de registro con estos signos (sin validar: lo hace RegistradorPacientes). */
	public DatosRegistro datos(String nombre, int edad, String queja) {
		return new DatosRegistro(nombre, edad, fc, sistolica, diastolica, spo2, temperatura, dolor, gcs, queja);
	}

}
