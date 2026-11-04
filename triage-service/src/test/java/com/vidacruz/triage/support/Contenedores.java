package com.vidacruz.triage.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contenedores compartidos por toda la JVM de tests (patron singleton): arrancan la primera vez que se piden
 * y los detiene Ryuk al terminar. Asi no se levanta un Postgres/Kafka por clase de test.
 */
public final class Contenedores {

	private Contenedores() {
	}

	private static final class PostgresHolder {
		static final PostgreSQLContainer INSTANCIA = iniciar();

		private static PostgreSQLContainer iniciar() {
			var contenedor = new PostgreSQLContainer("postgres:16-alpine");
			contenedor.start();
			return contenedor;
		}
	}

	private static final class KafkaHolder {
		static final KafkaContainer INSTANCIA = iniciar();

		private static KafkaContainer iniciar() {
			var contenedor = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
			contenedor.start();
			return contenedor;
		}
	}

	public static PostgreSQLContainer postgres() {
		return PostgresHolder.INSTANCIA;
	}

	public static KafkaContainer kafka() {
		return KafkaHolder.INSTANCIA;
	}

	/** Crea una base de datos vacia en el Postgres compartido, para tests que no deben compartir datos con el contexto Spring. */
	public static String jdbcUrlDeBaseNueva(String nombre) {
		var postgres = postgres();
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement s = c.createStatement()) {
			s.execute("CREATE DATABASE " + nombre);
		}
		catch (SQLException e) {
			throw new IllegalStateException("No se pudo crear la base " + nombre, e);
		}
		return "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
				postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), nombre);
	}

}
