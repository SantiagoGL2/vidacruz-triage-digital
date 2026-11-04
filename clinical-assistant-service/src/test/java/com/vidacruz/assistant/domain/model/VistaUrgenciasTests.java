package com.vidacruz.assistant.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.OperacionEstado.DarDeAlta;
import com.vidacruz.assistant.domain.model.OperacionEstado.Escalar;
import com.vidacruz.assistant.domain.model.OperacionEstado.PasarAConsulta;
import com.vidacruz.assistant.domain.model.OperacionEstado.Registrar;
import com.vidacruz.assistant.domain.model.enums.EstadoPaciente;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reglas de la proyeccion (RF-04 parte B), sin Redis ni Kafka. */
class VistaUrgenciasTests {

	private static final Instant T0 = Instant.parse("2026-11-04T15:00:00Z");

	private VistaUrgencias vista = VistaUrgencias.vacia();

	private ResultadoAplicacion aplicar(OperacionEstado op) {
		return aplicar(UUID.randomUUID(), T0, op);
	}

	private ResultadoAplicacion aplicar(UUID eventId, Instant cuando, OperacionEstado op) {
		var r = vista.aplicar(eventId, cuando, op);
		vista = r.vista();
		return r.resultado();
	}

	private static Registrar registrar(UUID id, NivelTriaje nivel) {
		return new Registrar(id, nivel, T0);
	}

	@Test
	void unaVistaVaciaTieneLosCincoNivelesEnCero() {
		EstadoUrgencias e = vista.estado();

		assertThat(e.totalActivos()).isZero();
		assertThat(e.porNivel()).hasSize(5).containsValues(0L, 0L, 0L, 0L, 0L);
		assertThat(e.escalados()).isZero();
		assertThat(e.ultimoEventoEn()).isNull();
	}

	@Test
	void registradoSumaUnoASuNivelYEntraEnEstadoRegistrado() {
		UUID id = UUID.randomUUID();

		assertThat(aplicar(registrar(id, NivelTriaje.URGENTE))).isEqualTo(ResultadoAplicacion.APLICADO);

		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);
		assertThat(vista.estado().totalActivos()).isEqualTo(1);
		assertThat(vista.pacientes().get(id).estado()).isEqualTo(EstadoPaciente.REGISTRADO);
	}

	@Test
	void enConsultaCambiaElEstadoPeroNoLosContadores() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.CRITICO));

		assertThat(aplicar(new PasarAConsulta(id))).isEqualTo(ResultadoAplicacion.APLICADO);

		assertThat(vista.pacientes().get(id).estado()).isEqualTo(EstadoPaciente.EN_CONSULTA);
		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
		assertThat(vista.estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void escaladoMarcaElFlagYNoCambiaLosContadoresPorNivel() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.URGENTE_2));

		assertThat(aplicar(new Escalar(id))).isEqualTo(ResultadoAplicacion.APLICADO);

		assertThat(vista.estado().escalados()).isEqualTo(1);
		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.URGENTE_2, 1L);
		assertThat(vista.pacientes().get(id).escalado()).isTrue();
	}

	@Test
	void escalarDosVecesConEventIdDistintoCuentaUnaSolaVez() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.CRITICO));
		aplicar(new Escalar(id));

		assertThat(aplicar(new Escalar(id))).isEqualTo(ResultadoAplicacion.SIN_CAMBIO);

		assertThat(vista.estado().escalados()).isEqualTo(1);
	}

	@Test
	void altaRestaUnoAlNivelQueTeniaEnLaVistaYLoElimina() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.NO_URGENTE));
		aplicar(registrar(UUID.randomUUID(), NivelTriaje.NO_URGENTE));

		assertThat(aplicar(new DarDeAlta(id))).isEqualTo(ResultadoAplicacion.APLICADO);

		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.NO_URGENTE, 1L);
		assertThat(vista.pacientes()).doesNotContainKey(id);
	}

	@Test
	void altaDeUnPacienteEscaladoTambienDescuentaLosEscalados() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.CRITICO));
		aplicar(new Escalar(id));

		aplicar(new DarDeAlta(id));

		assertThat(vista.estado().escalados()).isZero();
		assertThat(vista.estado().totalActivos()).isZero();
	}

	@Test
	void altaConsultaOEscalamientoDeUnPacienteDesconocidoSeIgnoraYNuncaDejaNegativos() {
		UUID desconocido = UUID.randomUUID();

		assertThat(aplicar(new DarDeAlta(desconocido))).isEqualTo(ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO);
		assertThat(aplicar(new PasarAConsulta(desconocido))).isEqualTo(ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO);
		assertThat(aplicar(new Escalar(desconocido))).isEqualTo(ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO);

		assertThat(vista.estado().porNivel().values()).allMatch(n -> n == 0);
		assertThat(vista.estado().escalados()).isZero();
	}

	@Test
	void altaRepetidaConOtroEventIdNoResta() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.URGENTE));
		aplicar(registrar(UUID.randomUUID(), NivelTriaje.URGENTE));
		aplicar(new DarDeAlta(id));

		assertThat(aplicar(new DarDeAlta(id))).isEqualTo(ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO);

		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);
	}

	@Test
	void registroDuplicadoDelMismoPacienteConOtroEventIdNoSumaDosVeces() {
		UUID id = UUID.randomUUID();
		aplicar(registrar(id, NivelTriaje.CRITICO));

		assertThat(aplicar(registrar(id, NivelTriaje.CRITICO))).isEqualTo(ResultadoAplicacion.IGNORADO_YA_REGISTRADO);

		assertThat(vista.estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
		assertThat(vista.estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void elMismoEventIdSeAplicaUnaSolaVez() {
		UUID id = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		assertThat(aplicar(eventId, T0, registrar(id, NivelTriaje.CRITICO))).isEqualTo(ResultadoAplicacion.APLICADO);

		assertThat(aplicar(eventId, T0, registrar(id, NivelTriaje.CRITICO))).isEqualTo(ResultadoAplicacion.DUPLICADO);

		assertThat(vista.estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void unEventIdDuplicadoNoDeshaceUnaAltaPosterior() {
		UUID id = UUID.randomUUID();
		UUID registro = UUID.randomUUID();
		aplicar(registro, T0, registrar(id, NivelTriaje.URGENTE));
		aplicar(new DarDeAlta(id));

		// reentrega tardia del registro: no resucita al paciente
		assertThat(aplicar(registro, T0, registrar(id, NivelTriaje.URGENTE))).isEqualTo(ResultadoAplicacion.DUPLICADO);

		assertThat(vista.estado().totalActivos()).isZero();
	}

	@Test
	void ultimoEventoEsElDeOccurredAtMasReciente() {
		UUID id = UUID.randomUUID();
		aplicar(UUID.randomUUID(), T0.plusSeconds(60), registrar(id, NivelTriaje.CRITICO));

		aplicar(UUID.randomUUID(), T0.plusSeconds(10), new PasarAConsulta(id));   // llega despues pero ocurrio antes

		assertThat(vista.estado().ultimoEventoEn()).isEqualTo(T0.plusSeconds(60));
	}

	@Test
	void estadoUrgenciasNuncaExponeContadoresNegativos() {
		var e = new EstadoUrgencias(java.util.Map.of(NivelTriaje.CRITICO, -3L), -1, null);

		assertThat(e.porNivel().get(NivelTriaje.CRITICO)).isZero();
		assertThat(e.escalados()).isZero();
	}

}
