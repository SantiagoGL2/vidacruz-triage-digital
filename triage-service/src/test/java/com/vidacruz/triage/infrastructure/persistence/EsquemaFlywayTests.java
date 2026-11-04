package com.vidacruz.triage.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Valida V1: la migracion aplica y las garantias de integridad (CHECKs, auditoria inmutable, escalamiento unico). */
@Testcontainers
class EsquemaFlywayTests {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	static final UUID PACIENTE = UUID.randomUUID();

	@BeforeAll
	static void migrar() throws SQLException {
		var resultado = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.load().migrate();
		assertThat(resultado.success).isTrue();
		ejecutar("""
				INSERT INTO pacientes (id, nombre_completo, edad, frecuencia_cardiaca, presion_sistolica, presion_diastolica,
				  spo2, temperatura, dolor, gcs, queja_principal, nivel, sla_minutos, fecha_registro, fecha_ultimo_cambio_estado)
				VALUES ('%s', 'Paciente Prueba', 40, 72, 120, 80, 99, 36.5, 2, 15, 'Herida por cortar', 4, 60, now(), now())
				""".formatted(PACIENTE));
		ejecutar("""
				INSERT INTO auditoria_clinica (paciente_id, estado_anterior, estado_nuevo, tipo_evento)
				VALUES ('%s', NULL, 'REGISTRADO', 'PACIENTE_REGISTRADO')
				""".formatted(PACIENTE));
	}

	@Test
	void auditoriaNoSePuedeModificarNiBorrar() {
		assertThatThrownBy(() -> ejecutar("UPDATE auditoria_clinica SET detalle = 'x'")).hasMessageContaining("inmutable");
		assertThatThrownBy(() -> ejecutar("DELETE FROM auditoria_clinica")).hasMessageContaining("inmutable");
		assertThatThrownBy(() -> ejecutar("TRUNCATE auditoria_clinica")).hasMessageContaining("inmutable");
	}

	@Test
	void soloUnEscalamientoAuditadoPorPaciente() throws SQLException {
		var insert = """
				INSERT INTO auditoria_clinica (paciente_id, estado_anterior, estado_nuevo, tipo_evento)
				VALUES ('%s', 'REGISTRADO', 'REGISTRADO', 'PACIENTE_ESCALADO')
				""".formatted(PACIENTE);
		ejecutar(insert);
		assertThatThrownBy(() -> ejecutar(insert)).hasMessageContaining("uq_auditoria_un_escalamiento");
	}

	@Test
	void checksRechazanValoresInvalidos() {
		assertThatThrownBy(() -> ejecutar("UPDATE pacientes SET nivel = 6")).hasMessageContaining("ck_pacientes_nivel");
		assertThatThrownBy(() -> ejecutar("UPDATE pacientes SET estado = 'DESCONOCIDO'")).hasMessageContaining("ck_pacientes_estado");
		assertThatThrownBy(() -> ejecutar("UPDATE pacientes SET presion_diastolica = 130")).hasMessageContaining("ck_pacientes_presion");
		assertThatThrownBy(() -> ejecutar("UPDATE pacientes SET spo2 = 101")).hasMessageContaining("ck_pacientes_spo2");
	}

	@Test
	void outboxRechazaTiposDeEventoDesconocidos() {
		assertThatThrownBy(() -> ejecutar("""
				INSERT INTO outbox_eventos (id, aggregate_id, event_type, payload)
				VALUES (gen_random_uuid(), gen_random_uuid(), 'otro_evento', '{}')
				""")).hasMessageContaining("ck_outbox_event_type");
	}

	private static void ejecutar(String sql) throws SQLException {
		try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

}
