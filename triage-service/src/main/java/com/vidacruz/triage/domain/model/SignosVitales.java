package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import java.util.ArrayList;
import java.util.List;

/**
 * Signos vitales validados en construccion (rangos y unidades en {@link RangoClinico}). Si hay datos invalidos se lanza
 * {@link DatoClinicoInvalidoException} con TODOS los campos erroneos.
 */
public record SignosVitales(
		int frecuenciaCardiaca,
		int presionSistolica,
		int presionDiastolica,
		int spo2,
		double temperatura,
		int dolor,
		int gcs) {

	public SignosVitales {
		List<CampoInvalido> errores = validar(frecuenciaCardiaca, presionSistolica, presionDiastolica, spo2,
				temperatura, dolor, gcs);
		if (!errores.isEmpty()) {
			throw new DatoClinicoInvalidoException(errores);
		}
	}

	/** Valida sin lanzar; permite a quien lo necesite combinar errores con los de otros campos. */
	public static List<CampoInvalido> validar(int frecuenciaCardiaca, int presionSistolica, int presionDiastolica,
			int spo2, double temperatura, int dolor, int gcs) {
		List<CampoInvalido> errores = new ArrayList<>();
		Validaciones.entero(errores, RangoClinico.FRECUENCIA_CARDIACA, frecuenciaCardiaca);
		int antesDePresiones = errores.size();
		Validaciones.entero(errores, RangoClinico.PRESION_SISTOLICA, presionSistolica);
		Validaciones.entero(errores, RangoClinico.PRESION_DIASTOLICA, presionDiastolica);
		// La relacion solo se evalua si ambas presiones son individualmente validas (evita errores duplicados).
		if (errores.size() == antesDePresiones && presionDiastolica >= presionSistolica) {
			errores.add(new CampoInvalido("presionDiastolica", presionDiastolica,
					"menor que la presion sistolica (%d)".formatted(presionSistolica)));
		}
		Validaciones.entero(errores, RangoClinico.SPO2, spo2);
		Validaciones.decimal(errores, RangoClinico.TEMPERATURA, temperatura);
		Validaciones.entero(errores, RangoClinico.DOLOR, dolor);
		Validaciones.entero(errores, RangoClinico.GCS, gcs);
		return errores;
	}

}
