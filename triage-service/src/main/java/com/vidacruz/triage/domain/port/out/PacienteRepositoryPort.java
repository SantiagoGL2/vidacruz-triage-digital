package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
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

	/**
	 * Ids de pacientes REGISTRADO, sin escalar, cuyo SLA vencio ({@code fechaRegistro + slaMinutos < ahora}, estricto),
	 * del mas antiguo al mas reciente. Usa el SLA guardado en cada paciente.
	 */
	Flux<UUID> buscarIdsConSlaVencido(Instant ahora, int limite);

	/**
	 * UPDATE condicional atomico (RN-03): marca el escalamiento solo si el paciente sigue REGISTRADO y sin escalar.
	 * Emite true si afecto 1 fila (esta llamada escalo) y false si no (otra ejecucion se adelanto o ya no aplica).
	 */
	Mono<Boolean> marcarEscalado(UUID id, Instant fechaEscalamiento);

}
