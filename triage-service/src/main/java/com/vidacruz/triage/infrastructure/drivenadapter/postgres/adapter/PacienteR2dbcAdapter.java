package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.PacienteEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.PacienteMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Operaciones estandar con {@link R2dbcEntityTemplate}: insert explicito (el id lo asigna la aplicacion),
 * select por id y update con {@code @Version}. {@code DatabaseClient} queda reservado para el SQL especial
 * (UPDATE condicional del escalamiento, SKIP LOCKED, reportes).
 */
@Component
class PacienteR2dbcAdapter implements PacienteRepositoryPort {

	/** Candidatos al escalamiento: SLA guardado en el paciente vencido (estrictamente), del mas antiguo al mas nuevo. */
	private static final String IDS_CON_SLA_VENCIDO = """
			SELECT id FROM pacientes
			 WHERE estado = 'REGISTRADO' AND escalado = false
			   AND fecha_registro + (sla_minutos * INTERVAL '1 minute') < :ahora
			 ORDER BY fecha_registro
			 LIMIT :limite
			""";

	/**
	 * UPDATE condicional atomico (RN-03). Sube tambien la version: si un cambio de estado concurrente leyo antes
	 * del escalamiento, su UPDATE con la version vieja falla (409) en vez de pisar el flag escalado.
	 */
	private static final String MARCAR_ESCALADO = """
			UPDATE pacientes
			   SET escalado = true, fecha_escalamiento = :fecha, version = version + 1
			 WHERE id = :id AND escalado = false AND estado = 'REGISTRADO'
			""";

	private final R2dbcEntityTemplate template;
	private final DatabaseClient db;

	PacienteR2dbcAdapter(R2dbcEntityTemplate template) {
		this.template = template;
		this.db = template.getDatabaseClient();
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
		return db.sql(IDS_CON_SLA_VENCIDO).bind("ahora", ahora).bind("limite", limite)
				.map(fila -> fila.get("id", UUID.class)).all();
	}

	@Override
	public Mono<Boolean> marcarEscalado(UUID id, Instant fechaEscalamiento) {
		return db.sql(MARCAR_ESCALADO).bind("fecha", fechaEscalamiento).bind("id", id)
				.fetch().rowsUpdated().map(filas -> filas == 1);
	}

}
