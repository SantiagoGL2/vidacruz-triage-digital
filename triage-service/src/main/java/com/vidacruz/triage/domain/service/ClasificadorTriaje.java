package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.SignosVitales;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.util.List;
import java.util.function.Predicate;

/**
 * Clasificacion automatica (RN-01). Los niveles 1, 2 y 3 se evaluan en ese orden; gana el primero con alguna
 * condicion cumplida. Si ninguno aplica es nivel 4, y nivel 5 si ademas la queja es administrativa.
 * Agregar o ajustar una condicion es agregar una {@link Regla} a la lista.
 */
public final class ClasificadorTriaje {

	/** Condicion de un nivel: descripcion legible (para auditoria) y predicado. */
	record Regla(NivelTriaje nivel, String descripcion, Predicate<SignosVitales> cumple) {
	}

	private static final String SIN_CRITERIOS = "Sin criterios de niveles 1-3";

	private static final List<NivelTriaje> NIVELES_EN_ORDEN =
			List.of(NivelTriaje.CRITICO, NivelTriaje.URGENTE, NivelTriaje.URGENTE_2);

	private static final List<Regla> REGLAS = List.of(
			new Regla(NivelTriaje.CRITICO, "FC < 40", s -> s.frecuenciaCardiaca() < 40),
			new Regla(NivelTriaje.CRITICO, "FC > 120", s -> s.frecuenciaCardiaca() > 120),
			new Regla(NivelTriaje.CRITICO, "SpO2 < 90", s -> s.spo2() < 90),
			new Regla(NivelTriaje.CRITICO, "P. sistolica < 90", s -> s.presionSistolica() < 90),
			new Regla(NivelTriaje.CRITICO, "GCS <= 8", s -> s.gcs() <= 8),

			new Regla(NivelTriaje.URGENTE, "90 <= SpO2 <= 93", s -> s.spo2() >= 90 && s.spo2() <= 93),
			new Regla(NivelTriaje.URGENTE, "90 <= P. sistolica <= 100",
					s -> s.presionSistolica() >= 90 && s.presionSistolica() <= 100),
			new Regla(NivelTriaje.URGENTE, "Temperatura >= 38.5", s -> s.temperatura() >= 38.5),
			new Regla(NivelTriaje.URGENTE, "9 <= GCS <= 13", s -> s.gcs() >= 9 && s.gcs() <= 13),

			new Regla(NivelTriaje.URGENTE_2, "90 <= FC <= 120",
					s -> s.frecuenciaCardiaca() >= 90 && s.frecuenciaCardiaca() <= 120),
			new Regla(NivelTriaje.URGENTE_2, "38.0 <= Temperatura < 38.5",
					s -> s.temperatura() >= 38.0 && s.temperatura() < 38.5),
			new Regla(NivelTriaje.URGENTE_2, "Dolor >= 7", s -> s.dolor() >= 7));

	private final QuejasAdministrativas quejasAdministrativas;

	public ClasificadorTriaje() {
		this(new QuejasAdministrativas());
	}

	public ClasificadorTriaje(QuejasAdministrativas quejasAdministrativas) {
		this.quejasAdministrativas = quejasAdministrativas;
	}

	public NivelTriaje clasificar(SignosVitales signos, String quejaPrincipal) {
		return evaluar(signos, quejaPrincipal).nivel();
	}

	/** Clasifica y explica: devuelve todas las condiciones cumplidas del nivel ganador. */
	public Clasificacion evaluar(SignosVitales signos, String quejaPrincipal) {
		for (NivelTriaje nivel : NIVELES_EN_ORDEN) {
			List<String> motivos = REGLAS.stream()
					.filter(r -> r.nivel() == nivel && r.cumple().test(signos))
					.map(Regla::descripcion)
					.toList();
			if (!motivos.isEmpty()) {
				return new Clasificacion(nivel, motivos);
			}
		}
		return quejasAdministrativas.coincidencia(quejaPrincipal)
				.map(frase -> new Clasificacion(NivelTriaje.NO_URGENTE_2,
						List.of(SIN_CRITERIOS, "Queja administrativa: " + frase)))
				.orElseGet(() -> new Clasificacion(NivelTriaje.NO_URGENTE, List.of(SIN_CRITERIOS)));
	}

}
