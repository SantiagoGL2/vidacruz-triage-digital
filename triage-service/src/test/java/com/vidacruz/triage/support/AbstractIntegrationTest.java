package com.vidacruz.triage.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de la mayoria de los tests de integracion: PostgreSQL y Kafka compartidos entre clases (singletons por JVM).
 * Una clase que necesite OTRO broker (p. ej. uno que se pueda tirar) extiende {@link AbstractPostgresTest} en vez de
 * esta: el {@code @DynamicPropertySource} de la clase base prevalece sobre el del hijo, no se puede sobrescribir.
 */
public abstract class AbstractIntegrationTest extends AbstractPostgresTest {

	@DynamicPropertySource
	static void kafkaCompartido(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", () -> Contenedores.kafka().getBootstrapServers());
	}

}
