package com.vidacruz.triage.support;

import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de los tests de integracion con contexto Spring: usa los contenedores compartidos de {@link Contenedores}.
 * No se usa {@code @ServiceConnection}: Boot 3.5 reconoce los contenedores 1.x (paquete org.testcontainers.containers),
 * no los de Testcontainers 2.x, asi que las propiedades se registran a mano.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	@DynamicPropertySource
	static void propiedadesDeContenedores(DynamicPropertyRegistry registry) {
		var postgres = Contenedores.postgres();
		registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(postgres.getHost(),
				postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), postgres.getDatabaseName()));
		registry.add("spring.r2dbc.username", postgres::getUsername);
		registry.add("spring.r2dbc.password", postgres::getPassword);
		registry.add("spring.flyway.url", postgres::getJdbcUrl);
		registry.add("spring.flyway.user", postgres::getUsername);
		registry.add("spring.flyway.password", postgres::getPassword);
		registry.add("spring.kafka.bootstrap-servers", () -> Contenedores.kafka().getBootstrapServers());
	}

}
