package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.exception.PacienteNoEncontradoException;
import com.vidacruz.triage.application.port.in.ConsultarPacienteUseCase;
import com.vidacruz.triage.application.result.PacienteConsultado;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import java.time.Clock;
import java.util.UUID;
import reactor.core.publisher.Mono;

public class ConsultarPacienteUseCaseImpl implements ConsultarPacienteUseCase {

	private final PacienteRepositoryPort pacientes;
	private final Clock clock;

	public ConsultarPacienteUseCaseImpl(PacienteRepositoryPort pacientes, Clock clock) {
		this.pacientes = pacientes;
		this.clock = clock;
	}

	@Override
	public Mono<PacienteConsultado> consultar(UUID id) {
		return pacientes.buscarPorId(id)
				.switchIfEmpty(Mono.error(() -> new PacienteNoEncontradoException(id)))
				.map(v -> new PacienteConsultado(v.paciente(), v.paciente().tiempoEnEstadoActual(clock.instant())));
	}

}
