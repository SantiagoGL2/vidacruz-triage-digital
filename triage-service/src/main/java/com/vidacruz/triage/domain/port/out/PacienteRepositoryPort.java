package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface PacienteRepositoryPort {

	Mono<Paciente> insertar(Paciente paciente);

	/** Vacio si no existe. */
	Mono<PacienteVersionado> buscarPorId(UUID id);

	/**
	 * Guarda el nuevo estado solo si la fila sigue en {@code versionLeida}; si no, falla con
	 * {@link com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException}.
	 */
	Mono<Void> actualizar(Paciente paciente, long versionLeida);

}
