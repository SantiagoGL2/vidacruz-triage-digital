package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import java.util.ArrayList;
import java.util.List;

/**
 * Signos vitales validados en construccion (tabla "Validaciones de datos"). Si hay datos invalidos se lanza
 * {@link DatoClinicoInvalidoException} con TODOS los campos erroneos.
 */
public record SignosVitales(
		int frecuenciaCardiaca,   // bpm
		int presionSistolica,     // mmHg
		int presionDiastolica,    // mmHg
		int spo2,                 // %
		double temperatura,       // grados C
		int dolor,                // 0-10
		int gcs) {                // 3-15

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
		Validaciones.entero(errores, "frecuenciaCardiaca", frecuenciaCardiaca, 20, 250, " bpm");
		int antesDePresiones = errores.size();
		Validaciones.entero(errores, "presionSistolica", presionSistolica, 60, 260, " mmHg");
		Validaciones.entero(errores, "presionDiastolica", presionDiastolica, 30, 160, " mmHg");
		// La relacion solo se evalua si ambas presiones son individualmente validas (evita errores duplicados).
		if (errores.size() == antesDePresiones && presionDiastolica >= presionSistolica) {
			errores.add(new CampoInvalido("presionDiastolica", presionDiastolica,
					"menor que la presion sistolica (%d)".formatted(presionSistolica)));
		}
		Validaciones.entero(errores, "spo2", spo2, 70, 100, " %");
		Validaciones.decimal(errores, "temperatura", temperatura, 34.0, 42.0, " C");
		Validaciones.entero(errores, "dolor", dolor, 0, 10, "");
		Validaciones.entero(errores, "gcs", gcs, 3, 15, "");
		return errores;
	}

}
