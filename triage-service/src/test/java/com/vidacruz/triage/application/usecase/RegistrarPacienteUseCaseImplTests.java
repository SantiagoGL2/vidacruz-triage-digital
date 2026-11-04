package com.vidacruz.triage.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidacruz.triage.application.command.RegistrarPacienteComando;
import com.vidacruz.triage.application.context.ContextoCorrelacion;
import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.model.enums.TipoEventoClinico;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class RegistrarPacienteUseCaseImplTests {

	PacienteRepositoryPort pacientes = mock(PacienteRepositoryPort.class);
	AuditoriaRepositoryPort auditoria = mock(AuditoriaRepositoryPort.class);
	OutboxRepositoryPort outbox = mock(OutboxRepositoryPort.class);
	MetricasPort metricas = mock(MetricasPort.class);
	Dobles.TransaccionFalsa transaccion = new Dobles.TransaccionFalsa();
	RegistrarPacienteUseCaseImpl servicio;

	@BeforeEach
	void preparar() {
		when(pacientes.insertar(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
		when(auditoria.registrar(any())).thenReturn(Mono.empty());
		when(outbox.guardar(any())).thenReturn(Mono.empty());
		servicio = new RegistrarPacienteUseCaseImpl(Dobles.registrador(), pacientes, auditoria, outbox, new FabricaEventos(1),
				transaccion, metricas, Dobles.RELOJ);
	}

	/** E1 del enunciado: FC 130, 180/110, SpO2 97, 36.8 C, dolor 8, GCS 15, dolor toracico -> CRITICO. */
	static RegistrarPacienteComando e1() {
		return new RegistrarPacienteComando("Paciente Prueba", 55, 130, 180, 110, 97, 36.8, 8, 15, "Dolor torácico");
	}

	@Test
	void registraClasificaYDejaAuditoriaYEventoEnLaMismaTransaccion() {
		StepVerifier.create(servicio.registrar(e1()).contextWrite(Context.of(ContextoCorrelacion.CLAVE, "corr-1")))
				.assertNext(p -> {
					assertThat(p.nivel()).isEqualTo(NivelTriaje.CRITICO);
					assertThat(p.slaMinutos()).isEqualTo(5);
					assertThat(p.estado()).isEqualTo(EstadoPaciente.REGISTRADO);
					assertThat(p.fechaRegistro()).isEqualTo(Dobles.AHORA);
				})
				.verifyComplete();

		var orden = inOrder(pacientes, auditoria, outbox);
		orden.verify(pacientes).insertar(any(Paciente.class));
		var cambio = ArgumentCaptor.forClass(CambioClinico.class);
		orden.verify(auditoria).registrar(cambio.capture());
		var evento = ArgumentCaptor.forClass(EventoTriaje.class);
		orden.verify(outbox).guardar(evento.capture());

		assertThat(cambio.getValue().tipoEvento()).isEqualTo(TipoEventoClinico.PACIENTE_REGISTRADO);
		assertThat(cambio.getValue().estadoAnterior()).isNull();
		assertThat(evento.getValue().eventType()).isEqualTo("paciente_registrado");
		assertThat(evento.getValue().correlationId()).isEqualTo("corr-1");
		assertThat(evento.getValue().payload().nivelNumero()).isEqualTo(1);
		assertThat(evento.getValue().payload().estadoAnterior()).isNull();
		assertThat(evento.getValue().payload().estadoNuevo()).isEqualTo("REGISTRADO");
		assertThat(evento.getValue().payload().slaMinutos()).isEqualTo(5);
		assertThat(transaccion.usos).hasValue(1);
		verify(metricas).pacienteClasificado(NivelTriaje.CRITICO);
	}

	@Test
	void datosInvalidosAcumulanErroresDePersonalesYSignosYNoTocanNada() {
		var comando = new RegistrarPacienteComando("ab", 200, 5, 180, 110, 50, 36.8, 8, 15, "Fiebre");

		StepVerifier.create(servicio.registrar(comando))
				.expectErrorSatisfies(e -> {
					assertThat(e).isInstanceOf(DatoClinicoInvalidoException.class);
					assertThat(((DatoClinicoInvalidoException) e).errores()).extracting(CampoInvalido::campo)
							.containsExactlyInAnyOrder("nombreCompleto", "edad", "frecuenciaCardiaca", "spo2");
				})
				.verify();

		verifyNoInteractions(pacientes, auditoria, outbox, metricas);
		assertThat(transaccion.usos).hasValue(0);
	}

	@Test
	void siFallaElOutboxElErrorPropagaPorLaTransaccionYNoSeCuentaLaMetrica() {
		when(outbox.guardar(any())).thenReturn(Mono.error(new IllegalStateException("outbox caido")));

		StepVerifier.create(servicio.registrar(e1()))
				.expectErrorMessage("outbox caido")
				.verify();

		// El rollback real lo hace la transaccion (se prueba en integracion); aqui: el error sale y no hay metrica.
		assertThat(transaccion.usos).hasValue(1);
		verify(metricas, never()).pacienteClasificado(any());
	}

	@Test
	void siFallaLaAuditoriaNoSeEscribeElEvento() {
		when(auditoria.registrar(any())).thenReturn(Mono.error(new IllegalStateException("auditoria caida")));

		StepVerifier.create(servicio.registrar(e1())).expectErrorMessage("auditoria caida").verify();

		verify(outbox, never()).guardar(any());
	}

	/** El caso de uso no tiene reglas: usa el registrador del dominio que le inyectan (aqui, con quejas y SLA propios). */
	@Test
	void noTieneReglasPropiasUsaElRegistradorDelDominio() {
		var registradorPropio = new com.vidacruz.triage.domain.service.RegistradorPacientes(
				new com.vidacruz.triage.domain.service.ClasificadorTriaje(
						new com.vidacruz.triage.domain.service.QuejasAdministrativas(java.util.List.of("certificado medico"))),
				nivel -> 7);
		var casoDeUso = new RegistrarPacienteUseCaseImpl(registradorPropio, pacientes, auditoria, outbox,
				new FabricaEventos(1), transaccion, metricas, Dobles.RELOJ);
		var comando = new RegistrarPacienteComando("Paciente Prueba", 40, 72, 120, 80, 99, 36.5, 2, 15, "Certificado médico");

		StepVerifier.create(casoDeUso.registrar(comando))
				.assertNext(p -> {
					assertThat(p.nivel()).isEqualTo(NivelTriaje.NO_URGENTE_2);
					assertThat(p.slaMinutos()).isEqualTo(7);
				})
				.verifyComplete();
	}

}
