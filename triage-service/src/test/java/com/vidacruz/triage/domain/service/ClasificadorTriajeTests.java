package com.vidacruz.triage.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.model.NivelTriaje;
import com.vidacruz.triage.domain.model.SignosVitales;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClasificadorTriajeTests {

	private final ClasificadorTriaje clasificador = new ClasificadorTriaje();

	private NivelTriaje nivel(Signos signos) {
		return clasificador.clasificar(signos.build(), "Dolor de cabeza");
	}

	// ---- Pacientes de ejemplo del enunciado (E1-E6) ----

	@ParameterizedTest(name = "{0} -> {9}")
	@CsvSource(delimiter = '|', textBlock = """
			E1 | 130 | 180 | 110 | 97 | 36.8 | 8 | 15 | Dolor torácico            | CRITICO
			E2 | 95  | 150 | 95  | 91 | 37.0 | 4 | 15 | Dificultad para respirar  | URGENTE
			E3 | 88  | 130 | 85  | 98 | 38.2 | 6 | 15 | Fiebre                    | URGENTE_2
			E4 | 72  | 120 | 80  | 99 | 36.5 | 2 | 15 | Herida por cortar         | NO_URGENTE
			E5 | 70  | 115 | 75  | 99 | 36.4 | 0 | 15 | Renovación de recetario   | NO_URGENTE_2
			E6 | 35  | 80  | 50  | 85 | 35.9 | 0 | 6  | Inconsciente              | CRITICO
			""")
	void clasificaPacientesDeEjemplo(String id, int fc, int sis, int dia, int spo2, double temp, int dolor, int gcs,
			String queja, NivelTriaje esperado) {
		SignosVitales signos = new SignosVitales(fc, sis, dia, spo2, temp, dolor, gcs);

		assertThat(clasificador.clasificar(signos, queja)).isEqualTo(esperado);
	}

	// ---- Bordes de cada umbral ----

	@ParameterizedTest(name = "FC {0} -> {1}")
	@CsvSource({"39,CRITICO", "40,NO_URGENTE", "89,NO_URGENTE", "90,URGENTE_2", "120,URGENTE_2", "121,CRITICO"})
	void bordesFrecuenciaCardiaca(int fc, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().fc(fc))).isEqualTo(esperado);
	}

	@ParameterizedTest(name = "SpO2 {0} -> {1}")
	@CsvSource({"89,CRITICO", "90,URGENTE", "93,URGENTE", "94,NO_URGENTE"})
	void bordesSpo2(int spo2, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().spo2(spo2))).isEqualTo(esperado);
	}

	@ParameterizedTest(name = "Sistolica {0} -> {1}")
	@CsvSource({"89,CRITICO", "90,URGENTE", "100,URGENTE", "101,NO_URGENTE"})
	void bordesPresionSistolica(int sistolica, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().sistolica(sistolica))).isEqualTo(esperado);
	}

	@ParameterizedTest(name = "Temperatura {0} -> {1}")
	@CsvSource({"37.9,NO_URGENTE", "38.0,URGENTE_2", "38.4,URGENTE_2", "38.5,URGENTE"})
	void bordesTemperatura(double temperatura, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().temperatura(temperatura))).isEqualTo(esperado);
	}

	@ParameterizedTest(name = "GCS {0} -> {1}")
	@CsvSource({"8,CRITICO", "9,URGENTE", "13,URGENTE", "14,NO_URGENTE"})
	void bordesGcs(int gcs, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().gcs(gcs))).isEqualTo(esperado);
	}

	@ParameterizedTest(name = "Dolor {0} -> {1}")
	@CsvSource({"6,NO_URGENTE", "7,URGENTE_2"})
	void bordesDolor(int dolor, NivelTriaje esperado) {
		assertThat(nivel(Signos.normales().dolor(dolor))).isEqualTo(esperado);
	}

	// ---- Precedencia ----

	@Test
	void condicionesDeNivel1Y3RigeElNivel1() {
		// FC 100 (nivel 3) + dolor 9 (nivel 3) + SpO2 85 (nivel 1)
		assertThat(nivel(Signos.normales().fc(100).dolor(9).spo2(85))).isEqualTo(NivelTriaje.CRITICO);
	}

	@Test
	void condicionesDeNivel2Y3RigeElNivel2() {
		assertThat(nivel(Signos.normales().fc(100).temperatura(38.6))).isEqualTo(NivelTriaje.URGENTE);
	}

	// ---- Nivel 5 (queja administrativa) ----

	@ParameterizedTest(name = "\"{0}\" es administrativa")
	@ValueSource(strings = {"Renovación de recetario", "RENOVACION DE RECETARIO", "renovacion de recetario medico",
			"  renovación   de  recetario ", "Vacunación", "vacunacion", "Seguimiento", "Documentos",
			"Recolección de resultados", "recoleccion de resultados de laboratorio"})
	void quejaAdministrativaConSignosNormalesEsNivel5(String queja) {
		assertThat(clasificador.clasificar(Signos.normales().build(), queja)).isEqualTo(NivelTriaje.NO_URGENTE_2);
	}

	@Test
	void quejaAdministrativaConSignosDeNivel3NoBajaA5() {
		SignosVitales signos = Signos.normales().fc(100).build();

		assertThat(clasificador.clasificar(signos, "Renovación de recetario")).isEqualTo(NivelTriaje.URGENTE_2);
	}

	@Test
	void quejaNoAdministrativaConSignosNormalesEsNivel4() {
		assertThat(clasificador.clasificar(Signos.normales().build(), "Herida por cortar")).isEqualTo(NivelTriaje.NO_URGENTE);
		assertThat(clasificador.clasificar(Signos.normales().build(), null)).isEqualTo(NivelTriaje.NO_URGENTE);
	}

	@Test
	void listaDeQuejasEsConfigurable() {
		var personalizado = new ClasificadorTriaje(new QuejasAdministrativas(List.of("certificado medico")));

		assertThat(personalizado.clasificar(Signos.normales().build(), "Certificado Médico")).isEqualTo(NivelTriaje.NO_URGENTE_2);
		assertThat(personalizado.clasificar(Signos.normales().build(), "vacunación")).isEqualTo(NivelTriaje.NO_URGENTE);
	}

	// ---- Motivos ----

	@Test
	void devuelveLosMotivosDelNivelGanador() {
		var clasificacion = clasificador.evaluar(Signos.normales().fc(130).spo2(85).dolor(9).build(), "Dolor torácico");

		assertThat(clasificacion.nivel()).isEqualTo(NivelTriaje.CRITICO);
		assertThat(clasificacion.motivos()).containsExactly("FC > 120", "SpO2 < 90");
	}

	@Test
	void motivoDeNivel4Y5() {
		assertThat(clasificador.evaluar(Signos.normales().build(), "Herida").motivos())
				.containsExactly("Sin criterios de niveles 1-3");
		assertThat(clasificador.evaluar(Signos.normales().build(), "Vacunación").motivos())
				.containsExactly("Sin criterios de niveles 1-3", "Queja administrativa: vacunacion");
	}

	@Test
	void listaConFraseVaciaSeRechaza() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> new QuejasAdministrativas(List.of("vacunación", "  ")))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
