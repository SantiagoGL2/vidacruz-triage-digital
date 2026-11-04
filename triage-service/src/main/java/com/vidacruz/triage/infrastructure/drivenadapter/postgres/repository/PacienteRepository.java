package com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository;

import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.PacienteEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.projection.PacienteIdProjection;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Consultas estaticas de pacientes. Solo lo usa {@code PacienteR2dbcAdapter}. */
public interface PacienteRepository extends R2dbcRepository<PacienteEntity, UUID> {

	/** Candidatos al escalamiento: SLA guardado en el paciente vencido (estrictamente), del mas antiguo al mas nuevo. */
	@Query("""
			SELECT id FROM pacientes
			 WHERE estado = 'REGISTRADO' AND escalado = false
			   AND fecha_registro + (sla_minutos * INTERVAL '1 minute') < :ahora
			 ORDER BY fecha_registro
			 LIMIT :limite
			""")
	Flux<PacienteIdProjection> buscarIdsConSlaVencido(@Param("ahora") Instant ahora, @Param("limite") int limite);

	/**
	 * UPDATE condicional atomico (RN-03): devuelve las filas afectadas (0 o 1). Sube tambien la version: si un cambio
	 * de estado concurrente leyo antes del escalamiento, su UPDATE con la version vieja falla (409) en vez de pisar
	 * el flag escalado.
	 */
	@Modifying
	@Query("""
			UPDATE pacientes
			   SET escalado = true, fecha_escalamiento = :fecha, version = version + 1
			 WHERE id = :id AND escalado = false AND estado = 'REGISTRADO'
			""")
	Mono<Integer> marcarEscalado(@Param("id") UUID id, @Param("fecha") Instant fecha);

}
