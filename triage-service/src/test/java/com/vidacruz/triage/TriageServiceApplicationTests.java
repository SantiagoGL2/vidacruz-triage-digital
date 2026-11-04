package com.vidacruz.triage;

import com.vidacruz.triage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Humo: el contexto arranca (Flyway + R2DBC + Kafka), las probes son publicas y el resto exige autenticacion. */
class TriageServiceApplicationTests extends AbstractIntegrationTest {

	@Autowired
	WebTestClient client;

	@Test
	void livenessYReadinessSonPublicas() {
		client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
		client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
	}

	@Test
	void endpointsDeNegocioExigenAutenticacion() {
		client.get().uri("/triage/pacientes/00000000-0000-0000-0000-000000000000").exchange()
				.expectStatus().isUnauthorized();
	}

}
