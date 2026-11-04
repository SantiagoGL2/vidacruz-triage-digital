package com.vidacruz.triage.support;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de los tests de integracion con contexto Spring y PostgreSQL compartido de {@link Contenedores}; activa BlockHound.
 * No configura Kafka: cada clase decide su broker (el compartido via {@link AbstractIntegrationTest} o uno propio). No se usa {@code @ServiceConnection}: Boot 3.5 reconoce los contenedores 1.x
 * (paquete org.testcontainers.containers), no los de Testcontainers 2.x, asi que las propiedades se registran a mano.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

	/** Coincide con triage.security.api-key de application-test.yml. */
	protected static final String API_KEY = "test-api-key";

	static {
		BlockHoundSupport.instalar();
	}

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	protected DatabaseClient db;

	@DynamicPropertySource
	static void propiedadesDePostgres(DynamicPropertyRegistry registry) {
		var postgres = Contenedores.postgres();
		registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
				postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), postgres.getDatabaseName()));
		registry.add("spring.r2dbc.username", postgres::getUsername);
		registry.add("spring.r2dbc.password", postgres::getPassword);
		registry.add("spring.flyway.url", postgres::getJdbcUrl);
		registry.add("spring.flyway.user", postgres::getUsername);
		registry.add("spring.flyway.password", postgres::getPassword);
	}

	/** Cliente con la API key valida. */
	protected WebTestClient api() {
		return webTestClient.mutate().responseTimeout(Duration.ofSeconds(30))
				.defaultHeader("X-API-Key", API_KEY).build();
	}

	/** Cliente sin credenciales. */
	protected WebTestClient anonimo() {
		return webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build();
	}

	protected List<String> tiposAuditoria(UUID pacienteId) {
		return db.sql("SELECT tipo_evento FROM auditoria_clinica WHERE paciente_id = :id")
				.bind("id", pacienteId).map(fila -> fila.get("tipo_evento", String.class)).all().collectList().block();
	}

	protected List<String> tiposOutbox(UUID pacienteId) {
		return db.sql("SELECT event_type FROM outbox_eventos WHERE aggregate_id = :id")
				.bind("id", pacienteId).map(fila -> fila.get("event_type", String.class)).all().collectList().block();
	}

	protected long contar(String tabla) {
		return db.sql("SELECT count(*) AS n FROM " + tabla)
				.map(fila -> fila.get("n", Long.class)).one().block();
	}

}
