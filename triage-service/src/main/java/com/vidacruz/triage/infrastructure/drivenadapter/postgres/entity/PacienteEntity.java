package com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Fila de {@code pacientes} (Spring Data R2DBC, no JPA). El {@code id} lo asigna la aplicacion, asi que el alta
 * se hace siempre con {@code R2dbcEntityTemplate.insert(...)} (un {@code save} lo confundiria con un update).
 * {@code version} es el candado optimista: un UPDATE con version vencida lanza OptimisticLockingFailureException.
 */
@Table("pacientes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PacienteEntity {

	@Id
	@Column("id")
	private UUID id;

	@Column("nombre_completo")
	private String nombreCompleto;

	@Column("edad")
	private int edad;

	@Column("frecuencia_cardiaca")
	private int frecuenciaCardiaca;

	@Column("presion_sistolica")
	private int presionSistolica;

	@Column("presion_diastolica")
	private int presionDiastolica;

	@Column("spo2")
	private int spo2;

	@Column("temperatura")
	private BigDecimal temperatura;

	@Column("dolor")
	private int dolor;

	@Column("gcs")
	private int gcs;

	@Column("queja_principal")
	private String quejaPrincipal;

	@Column("nivel")
	private int nivel;

	@Column("sla_minutos")
	private int slaMinutos;

	@Column("estado")
	private String estado;

	@Column("escalado")
	private boolean escalado;

	@Column("fecha_escalamiento")
	private Instant fechaEscalamiento;

	@Column("fecha_registro")
	private Instant fechaRegistro;

	@Column("fecha_primera_consulta")
	private Instant fechaPrimeraConsulta;

	@Column("fecha_ultimo_cambio_estado")
	private Instant fechaUltimoCambioEstado;

	@Version
	@Column("version")
	private Long version;

}
