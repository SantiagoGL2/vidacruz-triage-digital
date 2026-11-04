package com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity;

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

/** Fila de {@code auditoria_clinica} (RN-04). Sin version: la tabla es append-only y un trigger rechaza UPDATE/DELETE. */
@Table("auditoria_clinica")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEntity {

	@Id
	@Column("id")
	private UUID id;

	@Column("paciente_id")
	private UUID pacienteId;

	@Column("fecha_evento")
	private Instant fechaEvento;

	@Column("estado_anterior")
	private String estadoAnterior;

	@Column("estado_nuevo")
	private String estadoNuevo;

	@Column("tipo_evento")
	private String tipoEvento;

	@Column("detalle")
	private String detalle;

}
