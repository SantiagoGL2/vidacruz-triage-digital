package com.vidacruz.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValidacionesDatosTests {

	private static Arguments caso(String campo, Function<Signos, Signos> aplica) {
		return Arguments.of(campo, aplica);
	}

	static Stream<Arguments> limitesValidos() {
		return Stream.of(
				caso("fc min", s -> s.fc(20)), caso("fc max", s -> s.fc(250)),
				caso("sistolica min", s -> s.sistolica(60).diastolica(30)), caso("sistolica max", s -> s.sistolica(260)),
				caso("diastolica min", s -> s.diastolica(30)), caso("diastolica max", s -> s.sistolica(161).diastolica(160)),
				caso("diastolica = sistolica-1", s -> s.sistolica(100).diastolica(99)),
				caso("spo2 min", s -> s.spo2(70)), caso("spo2 max", s -> s.spo2(100)),
				caso("temp min", s -> s.temperatura(34.0)), caso("temp max", s -> s.temperatura(42.0)),
				caso("dolor min", s -> s.dolor(0)), caso("dolor max", s -> s.dolor(10)),
				caso("gcs min", s -> s.gcs(3)), caso("gcs max", s -> s.gcs(15)));
	}

	@ParameterizedTest(name = "{0} es valido")
	@MethodSource("limitesValidos")
	void aceptaLosLimitesExactos(String descripcion, Function<Signos, Signos> aplica) {
		assertThatCode(() -> aplica.apply(Signos.normales()).build()).doesNotThrowAnyException();
	}

	static Stream<Arguments> fueraDeRango() {
		return Stream.of(
				Arguments.of("frecuenciaCardiaca", (Function<Signos, Signos>) s -> s.fc(19)),
				Arguments.of("frecuenciaCardiaca", (Function<Signos, Signos>) s -> s.fc(251)),
				Arguments.of("presionSistolica", (Function<Signos, Signos>) s -> s.sistolica(59)),
				Arguments.of("presionSistolica", (Function<Signos, Signos>) s -> s.sistolica(261)),
				Arguments.of("presionDiastolica", (Function<Signos, Signos>) s -> s.diastolica(29)),
				Arguments.of("presionDiastolica", (Function<Signos, Signos>) s -> s.sistolica(200).diastolica(161)),
				Arguments.of("spo2", (Function<Signos, Signos>) s -> s.spo2(69)),
				Arguments.of("spo2", (Function<Signos, Signos>) s -> s.spo2(101)),
				Arguments.of("temperatura", (Function<Signos, Signos>) s -> s.temperatura(33.9)),
				Arguments.of("temperatura", (Function<Signos, Signos>) s -> s.temperatura(42.1)),
				Arguments.of("temperatura", (Function<Signos, Signos>) s -> s.temperatura(Double.NaN)),
				Arguments.of("dolor", (Function<Signos, Signos>) s -> s.dolor(-1)),
				Arguments.of("dolor", (Function<Signos, Signos>) s -> s.dolor(11)),
				Arguments.of("gcs", (Function<Signos, Signos>) s -> s.gcs(2)),
				Arguments.of("gcs", (Function<Signos, Signos>) s -> s.gcs(16)));
	}

	@ParameterizedTest(name = "{0} fuera de rango")
	@MethodSource("fueraDeRango")
	void rechazaValoresInmediatamenteFueraDeRango(String campo, Function<Signos, Signos> aplica) {
		assertThatThrownBy(() -> aplica.apply(Signos.normales()).build())
				.isInstanceOfSatisfying(DatoClinicoInvalidoException.class,
						e -> assertThat(e.errores()).extracting(CampoInvalido::campo).containsExactly(campo));
	}

	@Test
	void diastolicaIgualALaSistolicaEsInvalida() {
		assertThatThrownBy(() -> Signos.normales().sistolica(100).diastolica(100).build())
				.isInstanceOfSatisfying(DatoClinicoInvalidoException.class, e -> {
					assertThat(e.errores()).hasSize(1);
					assertThat(e.errores().get(0).campo()).isEqualTo("presionDiastolica");
					assertThat(e.errores().get(0).rangoEsperado()).contains("menor que la presion sistolica (100)");
				});
	}

	@Test
	void diastolicaMayorQueLaSistolicaEsInvalida() {
		assertThatThrownBy(() -> Signos.normales().sistolica(90).diastolica(120).build())
				.isInstanceOf(DatoClinicoInvalidoException.class);
	}

	@Test
	void reportaTodosLosCamposInvalidosJuntos() {
		assertThatThrownBy(() -> Signos.normales().fc(300).spo2(50).gcs(1).build())
				.isInstanceOfSatisfying(DatoClinicoInvalidoException.class, e -> {
					assertThat(e.errores()).extracting(CampoInvalido::campo)
							.containsExactlyInAnyOrder("frecuenciaCardiaca", "spo2", "gcs");
					assertThat(e.errores()).extracting(CampoInvalido::valorRecibido).contains(300, 50, 1);
					assertThat(e.getMessage()).contains("frecuenciaCardiaca=300").contains("20-250 bpm");
				});
	}

	// ---- Datos personales ----

	@Test
	void nombreSeValidaTrasTrim() {
		assertThat(Paciente.validarDatosPersonales("  ab  ", 30, "Fiebre")).extracting(CampoInvalido::campo)
				.containsExactly("nombreCompleto");
		assertThat(Paciente.validarDatosPersonales("  abc  ", 30, "Fiebre")).isEmpty();
		assertThat(Paciente.validarDatosPersonales("a".repeat(100), 30, "Fiebre")).isEmpty();
		assertThat(Paciente.validarDatosPersonales("a".repeat(101), 30, "Fiebre")).hasSize(1);
		assertThat(Paciente.validarDatosPersonales(null, 30, "Fiebre")).hasSize(1);
	}

	@Test
	void edadEntre0Y120() {
		assertThat(Paciente.validarDatosPersonales("Ana Perez", 0, "Fiebre")).isEmpty();
		assertThat(Paciente.validarDatosPersonales("Ana Perez", 120, "Fiebre")).isEmpty();
		assertThat(Paciente.validarDatosPersonales("Ana Perez", -1, "Fiebre")).extracting(CampoInvalido::campo).containsExactly("edad");
		assertThat(Paciente.validarDatosPersonales("Ana Perez", 121, "Fiebre")).extracting(CampoInvalido::campo).containsExactly("edad");
	}

	@Test
	void quejaEsObligatoria() {
		for (String queja : new String[] {null, "", "   "}) {
			assertThat(Paciente.validarDatosPersonales("Ana Perez", 30, queja)).extracting(CampoInvalido::campo)
					.containsExactly("quejaPrincipal");
		}
		assertThat(Paciente.validarDatosPersonales("Ana Perez", 30, "x".repeat(501))).hasSize(1);
	}

	@Test
	void datosPersonalesInvalidosSeReportanJuntos() {
		List<CampoInvalido> errores = Paciente.validarDatosPersonales("A", 200, " ");

		assertThat(errores).extracting(CampoInvalido::campo).containsExactly("nombreCompleto", "edad", "quejaPrincipal");
	}

}
