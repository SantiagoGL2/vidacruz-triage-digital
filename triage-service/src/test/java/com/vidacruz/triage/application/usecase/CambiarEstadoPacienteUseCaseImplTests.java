package com.vidacruz.triage.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.exception.TransicionInvalidaException;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.TipoEventoClinico;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CambiarEstadoPacienteUseCaseImplTests {

	PacienteRepositoryPort pacientes = mock(PacienteRepositoryPort.class);
	AuditoriaRepositoryPort auditoria = mock(AuditoriaRepositoryPort.class);
	OutboxRepositoryPort outbox = mock(OutboxRepositoryPort.class);
	Dobles.TransaccionFalsa transaccion = new Dobles.TransaccionFalsa();
	CambiarEstadoPacienteUseCaseImpl servicio;

	@BeforeEach
	void preparar() {
		when(pacientes.actualizar(any(), anyLong())).thenReturn(Mono.empty());
		when(auditoria.registrar(any())).thenReturn(Mono.empty());
		when(outbox.guardar(any())).thenReturn(Mono.empty());
		servicio = new CambiarEstadoPacienteUseCaseImpl(pacientes, auditoria, outbox, new FabricaEventos(1), transaccion,
				Dobles.RELOJ);
	}

	private Paciente existente(EstadoPaciente estado, long version) {
		Paciente p = Dobles.paciente(estado);
		when(pacientes.buscarPorId(p.id())).thenReturn(Mono.just(new PacienteVersionado(p, version)));
		return p;
	}

	@Test
	void registradoAEnConsultaGuardaConLaVersionLeidaYDejaAuditoriaYEvento() {
		Paciente p = existente(EstadoPaciente.REGISTRADO, 3);

		StepVerifier.create(servicio.cambiarEstado(p.id(), EstadoPaciente.EN_CONSULTA))
				.assertNext(nuevo -> {
					assertThat(nuevo.estado()).isEqualTo(EstadoPaciente.EN_CONSULTA);
					assertThat(nuevo.fechaPrimeraConsulta()).isEqualTo(Dobles.AHORA);
					assertThat(nuevo.fechaUltimoCambioEstado()).isEqualTo(Dobles.AHORA);
				})
				.verifyComplete();

		verify(pacientes).actualizar(any(Paciente.class), eq(3L));
		var cambio = ArgumentCaptor.forClass(CambioClinico.class);
		verify(auditoria).registrar(cambio.capture());
		assertThat(cambio.getValue().tipoEvento()).isEqualTo(TipoEventoClinico.PACIENTE_EN_CONSULTA);
		assertThat(cambio.getValue().estadoAnterior()).isEqualTo(EstadoPaciente.REGISTRADO);
		var evento = ArgumentCaptor.forClass(EventoTriaje.class);
		verify(outbox).guardar(evento.capture());
		assertThat(evento.getValue().eventType()).isEqualTo("paciente_en_consulta");
		assertThat(evento.getValue().payload().estadoAnterior()).isEqualTo("REGISTRADO");
		assertThat(evento.getValue().payload().estadoNuevo()).isEqualTo("EN_CONSULTA");
		assertThat(transaccion.usos).hasValue(1);
	}

	@Test
	void altaDirectaEmitePacienteAlta() {
		Paciente p = existente(EstadoPaciente.REGISTRADO, 0);

		StepVerifier.create(servicio.cambiarEstado(p.id(), EstadoPaciente.ALTA))
				.assertNext(nuevo -> assertThat(nuevo.fechaPrimeraConsulta()).isNull())
				.verifyComplete();

		var evento = ArgumentCaptor.forClass(EventoTriaje.class);
		verify(outbox).guardar(evento.capture());
		assertThat(evento.getValue().eventType()).isEqualTo("paciente_alta");
	}

	@Test
	void transicionInvalidaLanzaErrorDeDominioYNoEscribeNada() {
		Paciente p = existente(EstadoPaciente.ALTA, 5);

		StepVerifier.create(servicio.cambiarEstado(p.id(), EstadoPaciente.EN_CONSULTA))
				.expectError(TransicionInvalidaException.class)
				.verify();

		verify(pacientes, never()).actualizar(any(), anyLong());
		verify(auditoria, never()).registrar(any());
		verify(outbox, never()).guardar(any());
	}

	@Test
	void pacienteInexistente() {
		UUID id = UUID.randomUUID();
		when(pacientes.buscarPorId(id)).thenReturn(Mono.empty());

		StepVerifier.create(servicio.cambiarEstado(id, EstadoPaciente.ALTA))
				.expectError(PacienteNoEncontradoException.class)
				.verify();

		verify(pacientes, never()).actualizar(any(), anyLong());
	}

	@Test
	void conflictoDeConcurrenciaPropagaYNoAuditaNiPublica() {
		Paciente p = existente(EstadoPaciente.REGISTRADO, 0);
		when(pacientes.actualizar(any(), anyLong())).thenReturn(Mono.error(new ConflictoConcurrenciaException(p.id())));

		StepVerifier.create(servicio.cambiarEstado(p.id(), EstadoPaciente.EN_CONSULTA))
				.expectError(ConflictoConcurrenciaException.class)
				.verify();

		verify(auditoria, never()).registrar(any());
		verify(outbox, never()).guardar(any());
	}

}
