package com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository;

import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.OutboxEventoEntity;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.projection.EventoPendienteProjection;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Consultas estaticas del outbox. Solo lo usa {@code OutboxR2dbcAdapter}; no se expone fuera de
 * {@code drivenadapter.postgres}.
 */
public interface OutboxEventoRepository extends R2dbcRepository<OutboxEventoEntity, UUID> {

	/**
	 * Eventos publicables: sin publicar, con intentos disponibles y fuera de su backoff
	 * ({@code ultimo_intento_at + min(base * 2^(intentos-1), maximo)}). El NOT EXISTS entrega solo el evento mas
	 * antiguo pendiente de cada paciente (incluso si otra replica lo tiene reservado o esta en backoff), asi nunca se
	 * publica un evento antes que uno anterior del mismo paciente. SKIP LOCKED evita pisarse con otras replicas; los
	 * bloqueos viven mientras dure la transaccion reactiva que lo invoca.
	 */
	@Query("""
			SELECT o.id, o.aggregate_id, o.event_type, CAST(o.payload AS text) AS payload,
			       o.payload ->> 'correlationId' AS correlation_id, o.intentos, o.traceparent
			  FROM outbox_eventos o
			 WHERE o.published_at IS NULL
			   AND o.intentos < :maxIntentos
			   AND (o.ultimo_intento_at IS NULL
			        OR o.ultimo_intento_at + (LEAST(CAST(:baseMs AS double precision) * power(2, GREATEST(o.intentos - 1, 0)),
			                                        CAST(:maxMs AS double precision)) * INTERVAL '1 millisecond') <= :ahora)
			   AND NOT EXISTS (SELECT 1 FROM outbox_eventos p
			                    WHERE p.aggregate_id = o.aggregate_id AND p.published_at IS NULL
			                      AND (p.created_at < o.created_at OR (p.created_at = o.created_at AND p.id < o.id)))
			 ORDER BY o.created_at, o.id
			 LIMIT :limite
			 FOR UPDATE OF o SKIP LOCKED
			""")
	Flux<EventoPendienteProjection> reservarPendientes(@Param("maxIntentos") int maxIntentos,
			@Param("baseMs") long baseMs, @Param("maxMs") long maxMs, @Param("ahora") Instant ahora,
			@Param("limite") int limite);

	@Modifying
	@Query("""
			UPDATE outbox_eventos SET published_at = :cuando, ultimo_intento_at = :cuando, ultimo_error = NULL
			 WHERE id = :id
			""")
	Mono<Integer> marcarPublicado(@Param("id") UUID id, @Param("cuando") Instant cuando);

	@Modifying
	@Query("""
			UPDATE outbox_eventos SET intentos = intentos + 1, ultimo_intento_at = :cuando, ultimo_error = :motivo
			 WHERE id = :id
			""")
	Mono<Integer> registrarFallo(@Param("id") UUID id, @Param("motivo") String motivo, @Param("cuando") Instant cuando);

	@Query("SELECT count(*) FROM outbox_eventos WHERE published_at IS NULL AND intentos < :maxIntentos")
	Mono<Long> contarPendientes(@Param("maxIntentos") int maxIntentos);

}
