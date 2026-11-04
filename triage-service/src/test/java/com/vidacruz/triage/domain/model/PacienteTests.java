package com.vidacruz.triage.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.exception.TransicionInvalidaException;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.model.enums.TipoEventoClinico;
import com.vidacruz.triage.domain.service.ClasificadorTriaje;
import com.vidacruz.triage.domain.service.PoliticaSlaEstandar;
import com.vidacruz.triage.domain.service.RegistradorPacientes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PacienteTests {

	private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

	private final RegistradorPacientes registrador =
			new RegistradorPacientes(new ClasificadorTriaje(), new PoliticaSlaEstandar());

	/** Paciente NO_URGENTE (signos normales, queja no administrativa): SLA 60 min. */
	private Paciente nuevo() {
		return registrador.registrar(UUID.randomUUID(), Signos.normales().datos("Ana Perez", 35, "Herida por cortar"), T0)
				.paciente();
	}

	private Paciente conEstado(EstadoPaciente estado) {
		Paciente p = nuevo();
		return switch (estado) {
			case REGISTRADO -> p;
			case EN_CONSULTA -> p.cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plusSeconds(60)).paciente();
			case ALTA -> p.cambiarEstado(EstadoPaciente.ALTA, T0.plusSeconds(60)).paciente();
		};
	}

	// ---- Registro ----

	@Test
	void registroClasificaAsignaSlaYAudita() {
		CambioClinico cambio = registrador.registrar(UUID.randomUUID(),
				Signos.normales().fc(130).datos("  Ana Perez ", 35, " Dolor torácico "), T0);

		Paciente p = cambio.paciente();
		assertThat(p.nivel()).isEqualTo(NivelTriaje.CRITICO);
		assertThat(p.slaMinutos()).isEqualTo(5);
		assertThat(p.estado()).isEqualTo(EstadoPaciente.REGISTRADO);
		assertThat(p.escalado()).isFalse();
		assertThat(p.nombreCompleto()).isEqualTo("Ana Perez");
		assertThat(p.quejaPrincipal()).isEqualTo("Dolor torácico");
		assertThat(p.fechaRegistro()).isEqualTo(T0);
		assertThat(p.fechaPrimeraConsulta()).isNull();
		assertThat(cambio.estadoAnterior()).isNull();
		assertThat(cambio.estadoNuevo()).isEqualTo(EstadoPaciente.REGISTRADO);
		assertThat(cambio.tipoEvento()).isEqualTo(TipoEventoClinico.PACIENTE_REGISTRADO);
		assertThat(cambio.detalle()).contains("CRITICO").contains("FC > 120");
	}

	@Test
	void registroUsaLaPoliticaDeSlaConfigurada() {
		var politica = new PoliticaSlaEstandar(Map.of(NivelTriaje.CRITICO, 1, NivelTriaje.URGENTE, 2,
				NivelTriaje.URGENTE_2, 3, NivelTriaje.NO_URGENTE, 4, NivelTriaje.NO_URGENTE_2, 5));
		var reg = new RegistradorPacientes(new ClasificadorTriaje(), politica);

		assertThat(reg.registrar(UUID.randomUUID(), Signos.normales().datos("Ana Perez", 35, "Herida"), T0)
				.paciente().slaMinutos()).isEqualTo(4);
	}

	@Test
	void registroConDatosPersonalesInvalidosFalla() {
		assertThatThrownBy(() -> registrador.registrar(UUID.randomUUID(), Signos.normales().datos("A", 500, ""), T0))
				.isInstanceOf(DatoClinicoInvalidoException.class)
				.satisfies(e -> assertThat(((DatoClinicoInvalidoException) e).errores()).hasSize(3));
	}

	// ---- Maquina de estados ----

	@ParameterizedTest(name = "{0} -> {1} es valida")
	@CsvSource({"REGISTRADO,EN_CONSULTA,PACIENTE_EN_CONSULTA", "REGISTRADO,ALTA,PACIENTE_ALTA", "EN_CONSULTA,ALTA,PACIENTE_ALTA"})
	void transicionesValidas(EstadoPaciente desde, EstadoPaciente hacia, TipoEventoClinico evento) {
		Paciente p = conEstado(desde);
		Instant ahora = T0.plus(Duration.ofMinutes(7));

		CambioClinico cambio = p.cambiarEstado(hacia, ahora);

		assertThat(cambio.estadoAnterior()).isEqualTo(desde);
		assertThat(cambio.estadoNuevo()).isEqualTo(hacia);
		assertThat(cambio.tipoEvento()).isEqualTo(evento);
		assertThat(cambio.ocurridoEn()).isEqualTo(ahora);
		assertThat(cambio.paciente().estado()).isEqualTo(hacia);
		assertThat(cambio.paciente().fechaUltimoCambioEstado()).isEqualTo(ahora);
		assertThat(p.estado()).as("el agregado original es inmutable").isEqualTo(desde);
	}

	@ParameterizedTest(name = "{0} -> {1} es invalida")
	@CsvSource({"REGISTRADO,REGISTRADO", "EN_CONSULTA,REGISTRADO", "EN_CONSULTA,EN_CONSULTA", "ALTA,REGISTRADO",
			"ALTA,EN_CONSULTA", "ALTA,ALTA"})
	void transicionesInvalidas(EstadoPaciente desde, EstadoPaciente hacia) {
		Paciente p = conEstado(desde);

		assertThatThrownBy(() -> p.cambiarEstado(hacia, T0.plusSeconds(120)))
				.isInstanceOfSatisfying(TransicionInvalidaException.class, e -> {
					assertThat(e.desde()).isEqualTo(desde);
					assertThat(e.hacia()).isEqualTo(hacia);
				});
	}

	@Test
	void mensajeDescriptivoDeTransicionInvalida() {
		assertThatThrownBy(() -> conEstado(EstadoPaciente.ALTA).cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plusSeconds(120)))
				.hasMessageContaining("No se puede pasar de ALTA a EN_CONSULTA");
		assertThatThrownBy(() -> nuevo().cambiarEstado(EstadoPaciente.REGISTRADO, T0.plusSeconds(120)))
				.hasMessageContaining("ya se encuentra en estado REGISTRADO");
	}

	@Test
	void primeraEntradaAConsultaFijaFechaPrimeraConsulta() {
		Instant ahora = T0.plus(Duration.ofMinutes(12));

		Paciente enConsulta = nuevo().cambiarEstado(EstadoPaciente.EN_CONSULTA, ahora).paciente();

		assertThat(enConsulta.fechaPrimeraConsulta()).isEqualTo(ahora);
		Paciente alta = enConsulta.cambiarEstado(EstadoPaciente.ALTA, ahora.plus(Duration.ofMinutes(30))).paciente();
		assertThat(alta.fechaPrimeraConsulta()).as("el alta no la modifica").isEqualTo(ahora);
	}

	@Test
	void altaDirectaNoFijaFechaPrimeraConsulta() {
		Paciente alta = nuevo().cambiarEstado(EstadoPaciente.ALTA, T0.plusSeconds(90)).paciente();

		assertThat(alta.fechaPrimeraConsulta()).isNull();
	}

	@Test
	void tiempoEnEstadoActualSeMideDesdeElUltimoCambio() {
		Paciente enConsulta = nuevo().cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plus(Duration.ofMinutes(10))).paciente();

		assertThat(enConsulta.tiempoEnEstadoActual(T0.plus(Duration.ofMinutes(25)))).isEqualTo(Duration.ofMinutes(15));
	}

	// ---- SLA y escalamiento ----

	@Test
	void superaElSlaSoloDespuesDelVencimiento() {
		Paciente p = nuevo(); // SLA 60 min

		assertThat(p.vencimientoSla()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
		assertThat(p.haSuperadoSla(T0.plus(Duration.ofMinutes(59)))).isFalse();
		assertThat(p.haSuperadoSla(T0.plus(Duration.ofMinutes(60)))).as("justo en el limite aun no lo supera").isFalse();
		assertThat(p.haSuperadoSla(T0.plus(Duration.ofMinutes(60)).plusMillis(1))).isTrue();
	}

	@Test
	void escalaSiSuperoElSla() {
		Paciente p = nuevo();
		Instant ahora = T0.plus(Duration.ofMinutes(61));

		CambioClinico cambio = p.escalar(ahora).orElseThrow();

		assertThat(cambio.paciente().escalado()).isTrue();
		assertThat(cambio.paciente().fechaEscalamiento()).isEqualTo(ahora);
		assertThat(cambio.paciente().estado()).as("el escalamiento no cambia el estado").isEqualTo(EstadoPaciente.REGISTRADO);
		assertThat(cambio.estadoAnterior()).isEqualTo(EstadoPaciente.REGISTRADO);
		assertThat(cambio.estadoNuevo()).isEqualTo(EstadoPaciente.REGISTRADO);
		assertThat(cambio.tipoEvento()).isEqualTo(TipoEventoClinico.PACIENTE_ESCALADO);
		assertThat(cambio.ocurridoEn()).isEqualTo(ahora);
		assertThat(cambio.detalle()).contains("60").contains("61");
		assertThat(cambio.paciente().fechaUltimoCambioEstado()).isEqualTo(T0);
	}

	@Test
	void noEscalaSiNoSuperoElSla() {
		assertThat(nuevo().escalar(T0.plus(Duration.ofMinutes(30)))).isEmpty();
		assertThat(nuevo().escalar(T0.plus(Duration.ofMinutes(60)))).isEmpty();
	}

	@Test
	void noEscalaPorSegundaVez() {
		Paciente escalado = nuevo().escalar(T0.plus(Duration.ofMinutes(61))).orElseThrow().paciente();

		assertThat(escalado.escalar(T0.plus(Duration.ofMinutes(90)))).isEmpty();
		assertThat(escalado.escalar(T0.plus(Duration.ofMinutes(61)).plusSeconds(1))).isEmpty();
	}

	@ParameterizedTest(name = "nunca escala en {0}")
	@CsvSource({"EN_CONSULTA", "ALTA"})
	void nuncaEscalaFueraDeRegistrado(EstadoPaciente estado) {
		Paciente p = conEstado(estado);

		assertThat(p.escalar(T0.plus(Duration.ofHours(10)))).isEmpty();
	}

	@Test
	void pacienteEscaladoQueEntraAConsultaConservaElFlag() {
		Paciente escalado = nuevo().escalar(T0.plus(Duration.ofMinutes(61))).orElseThrow().paciente();

		Paciente enConsulta = escalado.cambiarEstado(EstadoPaciente.EN_CONSULTA, T0.plus(Duration.ofMinutes(65))).paciente();

		assertThat(enConsulta.escalado()).isTrue();
		assertThat(enConsulta.fechaEscalamiento()).isEqualTo(escalado.fechaEscalamiento());
	}

}
