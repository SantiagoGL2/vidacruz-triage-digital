package com.vidacruz.triage.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ConsultarPacienteUseCaseImplTests {

	PacienteRepositoryPort pacientes = mock(PacienteRepositoryPort.class);
	ConsultarPacienteUseCaseImpl servicio = new ConsultarPacienteUseCaseImpl(pacientes, Dobles.RELOJ);

	@Test
	void calculaElTiempoEnElEstadoActualConElClockInyectado() {
		// registrado 10 min antes de AHORA y sin cambios de estado -> 10 min en REGISTRADO
		Paciente p = Dobles.paciente(EstadoPaciente.REGISTRADO);
		when(pacientes.buscarPorId(p.id())).thenReturn(Mono.just(new PacienteVersionado(p, 0)));

		StepVerifier.create(servicio.consultar(p.id()))
				.assertNext(c -> {
					assertThat(c.paciente()).isEqualTo(p);
					assertThat(c.tiempoEnEstadoActual()).isEqualTo(Duration.ofMinutes(10));
				})
				.verifyComplete();
	}

	@Test
	void elTiempoCuentaDesdeElUltimoCambioDeEstado() {
		// en consulta desde 1 min despues del registro -> 9 min
		Paciente p = Dobles.paciente(EstadoPaciente.EN_CONSULTA);
		when(pacientes.buscarPorId(p.id())).thenReturn(Mono.just(new PacienteVersionado(p, 1)));

		StepVerifier.create(servicio.consultar(p.id()))
				.assertNext(c -> assertThat(c.tiempoEnEstadoActual()).isEqualTo(Duration.ofMinutes(9)))
				.verifyComplete();
	}

	@Test
	void pacienteInexistente() {
		UUID id = UUID.randomUUID();
		when(pacientes.buscarPorId(id)).thenReturn(Mono.empty());

		StepVerifier.create(servicio.consultar(id)).expectError(PacienteNoEncontradoException.class).verify();
	}

}
