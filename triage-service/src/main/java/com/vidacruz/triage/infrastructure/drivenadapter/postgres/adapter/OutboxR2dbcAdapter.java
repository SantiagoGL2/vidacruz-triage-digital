package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.OutboxEventoMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbox en PostgreSQL. La escritura usa el template (misma transaccion que el cambio); la lectura del publicador
 * necesita SQL especial (FOR UPDATE SKIP LOCKED, backoff, orden por paciente) y va con DatabaseClient.
 */
@Component
class OutboxR2dbcAdapter implements OutboxRepositoryPort {

	/**
	 * Eventos publicables: sin publicar, con intentos disponibles y fuera de su backoff
	 * ({@code ultimo_intento_at + min(base * 2^(intentos-1), maximo)}). El NOT EXISTS entrega solo el evento mas
	 * antiguo pendiente de cada paciente (incluso si otra replica lo tiene reservado o esta en backoff), asi nunca se
	 * publica un evento antes que uno anterior del mismo paciente. SKIP LOCKED evita pisarse con otras replicas.
	 */
	private static final String RESERVAR = """
			SELECT o.id, o.aggregate_id, o.event_type, o.payload::text AS payload,
			       o.payload ->> 'correlationId' AS correlation_id, o.intentos
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
			""";

	private static final String MARCAR_PUBLICADO = """
			UPDATE outbox_eventos SET published_at = :cuando, ultimo_intento_at = :cuando, ultimo_error = NULL
			 WHERE id = :id
			""";

	private static final String REGISTRAR_FALLO = """
			UPDATE outbox_eventos SET intentos = intentos + 1, ultimo_intento_at = :cuando, ultimo_error = :motivo
			 WHERE id = :id
			""";

	private static final String CONTAR_PENDIENTES = """
			SELECT count(*) AS n FROM outbox_eventos WHERE published_at IS NULL AND intentos < :maxIntentos
			""";

	private final R2dbcEntityTemplate template;
	private final DatabaseClient db;
	private final OutboxEventoMapper mapper;

	OutboxR2dbcAdapter(R2dbcEntityTemplate template, OutboxEventoMapper mapper) {
		this.template = template;
		this.db = template.getDatabaseClient();
		this.mapper = mapper;
	}

	@Override
	public Mono<Void> guardar(EventoTriaje evento) {
		return Mono.fromSupplier(() -> mapper.aEntidad(evento)).flatMap(template::insert).then();
	}

	@Override
	public Flux<EventoPendiente> reservarPendientes(int limite, Instant ahora, PoliticaReintentoOutbox politica) {
		return db.sql(RESERVAR)
				.bind("maxIntentos", politica.maxIntentos())
				.bind("baseMs", politica.backoffInicial().toMillis())
				.bind("maxMs", politica.backoffMaximo().toMillis())
				.bind("ahora", ahora)
				.bind("limite", limite)
				.map(fila -> new EventoPendiente(
						fila.get("id", UUID.class),
						fila.get("aggregate_id", UUID.class),
						fila.get("event_type", String.class),
						fila.get("payload", String.class),
						fila.get("correlation_id", String.class),
						fila.get("intentos", Integer.class)))
				.all();
	}

	@Override
	public Mono<Void> marcarPublicado(UUID eventId, Instant cuando) {
		return db.sql(MARCAR_PUBLICADO).bind("cuando", cuando).bind("id", eventId).fetch().rowsUpdated().then();
	}

	@Override
	public Mono<Void> registrarFallo(UUID eventId, String motivo, Instant cuando) {
		return db.sql(REGISTRAR_FALLO).bind("cuando", cuando).bind("motivo", motivo).bind("id", eventId)
				.fetch().rowsUpdated().then();
	}

	@Override
	public Mono<Long> contarPendientes(int maxIntentos) {
		return db.sql(CONTAR_PENDIENTES).bind("maxIntentos", maxIntentos).map(fila -> fila.get("n", Long.class)).one();
	}

}
