package com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity;

import io.r2dbc.postgresql.codec.Json;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Fila de {@code outbox_eventos}: el envelope completo va como JSONB (codec {@link Json} de r2dbc-postgresql). */
@Table("outbox_eventos")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventoEntity {

	@Id
	@Column("id")
	private UUID id;

	@Column("aggregate_id")
	private UUID aggregateId;

	@Column("event_type")
	private String eventType;

	@Column("payload")
	private Json payload;

	@Column("created_at")
	private Instant createdAt;

	@Column("published_at")
	private Instant publishedAt;

	@Column("intentos")
	private int intentos;

}
