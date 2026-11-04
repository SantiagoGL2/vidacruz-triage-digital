package com.vidacruz.triage.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.model.DatosRegistro;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El registro valida TODO en el dominio: datos personales y signos vitales juntos, en una sola excepcion. */
class RegistradorPacientesTests {

	private static final Instant AHORA = Instant.parse("2026-11-04T10:00:00Z");

	private final RegistradorPacientes registrador = new RegistradorPacientes(new ClasificadorTriaje(), new PoliticaSlaEstandar());

	private List<String> camposInvalidos(DatosRegistro datos) {
		return ((DatoClinicoInvalidoException) catchThrowableOfType(datos)).errores().stream()
				.map(CampoInvalido::campo).toList();
	}

	private Throwable catchThrowableOfType(DatosRegistro datos) {
		return org.assertj.core.api.Assertions.catchThrowable(() -> registrador.registrar(UUID.randomUUID(), datos, AHORA));
	}

	@Test
	void reportaJuntosLosErroresDeDatosPersonalesYDeSignosVitales() {
		// personales: nombre, edad, queja | signos: frecuenciaCardiaca, spo2
		var datos = Signos.normales().fc(5).spo2(50).datos("ab", 200, "  ");

		assertThat(camposInvalidos(datos)).containsExactlyInAnyOrder(
				"nombreCompleto", "edad", "quejaPrincipal", "frecuenciaCardiaca", "spo2");
	}

	@Test
	void unaSolaExcepcionConTodosLosCamposYSusValores() {
		var datos = Signos.normales().fc(5).datos("ab", 35, "Fiebre");

		assertThatThrownBy(() -> registrador.registrar(UUID.randomUUID(), datos, AHORA))
				.isInstanceOfSatisfying(DatoClinicoInvalidoException.class, e -> {
					assertThat(e.errores()).extracting(CampoInvalido::campo)
							.containsExactlyInAnyOrder("nombreCompleto", "frecuenciaCardiaca");
					assertThat(e.errores()).filteredOn(c -> c.campo().equals("frecuenciaCardiaca"))
							.singleElement().satisfies(c -> assertThat(c.valorRecibido()).isEqualTo(5));
				});
	}

	@Test
	void soloSignosInvalidosNoMezclaCamposPersonales() {
		var datos = Signos.normales().sistolica(100).diastolica(100).datos("Ana Perez", 35, "Fiebre");

		assertThat(camposInvalidos(datos)).containsExactly("presionDiastolica");
	}

	@Test
	void soloDatosPersonalesInvalidos() {
		var datos = Signos.normales().datos("Ana Perez", 121, "Fiebre");

		assertThat(camposInvalidos(datos)).containsExactly("edad");
	}

	@Test
	void datosValidosSeClasificanYRecibenElSla() {
		// E1 del enunciado
		var datos = new DatosRegistro("Maria Perez", 55, 130, 180, 110, 97, 36.8, 8, 15, "Dolor torácico");

		var cambio = registrador.registrar(UUID.randomUUID(), datos, AHORA);

		assertThat(cambio.paciente().nivel()).isEqualTo(NivelTriaje.CRITICO);
		assertThat(cambio.paciente().slaMinutos()).isEqualTo(5);
		assertThat(cambio.paciente().fechaRegistro()).isEqualTo(AHORA);
	}

	@Test
	void usaLasQuejasAdministrativasDelClasificadorQueRecibe() {
		var conLista = new RegistradorPacientes(
				new ClasificadorTriaje(new QuejasAdministrativas(List.of("certificado medico"))), new PoliticaSlaEstandar());
		var normales = Signos.normales();

		assertThat(conLista.registrar(UUID.randomUUID(), normales.datos("Ana Perez", 35, "Certificado médico"), AHORA)
				.paciente().nivel()).isEqualTo(NivelTriaje.NO_URGENTE_2);
		// con la lista del enunciado, esa misma queja no es administrativa
		assertThat(registrador.registrar(UUID.randomUUID(), normales.datos("Ana Perez", 35, "Certificado médico"), AHORA)
				.paciente().nivel()).isEqualTo(NivelTriaje.NO_URGENTE);
	}

}
