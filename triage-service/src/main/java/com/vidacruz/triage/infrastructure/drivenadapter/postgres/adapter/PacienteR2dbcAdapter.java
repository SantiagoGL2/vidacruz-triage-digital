package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.PacienteEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.PacienteMapper;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.projection.PacienteIdProjection;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository.PacienteRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Operaciones estandar con {@link R2dbcEntityTemplate}: insert explicito (el id lo asigna la aplicacion),
 * select por id y update con {@code @Version}. El SQL especial del escalamiento es estatico y vive en
 * {@link PacienteRepository}.
 */
@Component
class PacienteR2dbcAdapter implements PacienteRepositoryPort {

	private final R2dbcEntityTemplate template;
	private final PacienteRepository repositorio;

	PacienteR2dbcAdapter(R2dbcEntityTemplate template, PacienteRepository repositorio) {
		this.template = template;
		this.repositorio = repositorio;
	}

	@Override
	public Mono<Paciente> insertar(Paciente paciente) {
		return template.insert(PacienteMapper.aEntidad(paciente, null)).thenReturn(paciente);
	}

	@Override
	public Mono<PacienteVersionado> buscarPorId(UUID id) {
		return template.selectOne(query(where("id").is(id)), PacienteEntity.class)
				.map(e -> new PacienteVersionado(PacienteMapper.aDominio(e), e.getVersion()));
	}

	@Override
	public Mono<Void> actualizar(Paciente paciente, long versionLeida) {
		// UPDATE ... WHERE id = ? AND version = ?; si no afecta filas, Spring Data lanza OptimisticLockingFailureException.
		return template.update(PacienteMapper.aEntidad(paciente, versionLeida))
				.onErrorMap(OptimisticLockingFailureException.class, e -> new ConflictoConcurrenciaException(paciente.id()))
				.then();
	}

	@Override
	public Flux<UUID> buscarIdsConSlaVencido(Instant ahora, int limite) {
		return repositorio.buscarIdsConSlaVencido(ahora, limite).map(PacienteIdProjection::id);
	}

	@Override
	public Mono<Boolean> marcarEscalado(UUID id, Instant fechaEscalamiento) {
		// La fila afectada decide la idempotencia (RN-03): 1 = esta llamada escalo; 0 = otro ya lo hizo o ya no es REGISTRADO.
		return repositorio.marcarEscalado(id, fechaEscalamiento).map(filas -> filas == 1);
	}

}
