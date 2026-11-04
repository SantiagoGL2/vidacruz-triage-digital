package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.application.port.in.CambiarEstadoPacienteUseCase;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** RF-02: lee con su version, aplica la transicion del dominio y guarda + audita + outbox en una transaccion. */
@Slf4j
public class CambiarEstadoPacienteUseCaseImpl implements CambiarEstadoPacienteUseCase {

	private final PacienteRepositoryPort pacientes;
	private final RegistroCambioClinico registroCambio;
	private final TransaccionPort transaccion;
	private final Clock clock;

	public CambiarEstadoPacienteUseCaseImpl(PacienteRepositoryPort pacientes, AuditoriaRepositoryPort auditoria,
			OutboxRepositoryPort outbox, FabricaEventos fabricaEventos, TransaccionPort transaccion, Clock clock) {
		this.pacientes = pacientes;
		this.registroCambio = new RegistroCambioClinico(auditoria, outbox, fabricaEventos);
		this.transaccion = transaccion;
		this.clock = clock;
	}

	@Override
	public Mono<Paciente> cambiarEstado(UUID id, EstadoPaciente destino) {
		return transaccion.enTransaccion(
				pacientes.buscarPorId(id)
						.switchIfEmpty(Mono.error(() -> new PacienteNoEncontradoException(id)))
						.flatMap(actual -> aplicar(actual, destino)))
				.doOnSuccess(p -> log.info("Paciente id={} nivel={} paso a estado={}", p.id(), p.nivel(), p.estado()));
	}

	private Mono<Paciente> aplicar(PacienteVersionado actual, EstadoPaciente destino) {
		// Si la transicion no procede, el dominio lanza TransicionInvalidaException y flatMap la vuelve error del flujo.
		CambioClinico cambio = actual.paciente().cambiarEstado(destino, clock.instant());
		return pacientes.actualizar(cambio.paciente(), actual.version())
				.then(Mono.defer(() -> registroCambio.registrar(cambio)))
				.thenReturn(cambio.paciente());
	}

}
