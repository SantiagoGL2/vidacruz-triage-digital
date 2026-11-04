package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

import com.vidacruz.assistant.domain.model.OperacionEstado;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.Contenedores;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.testcontainers.DockerClientFactory;

/** GET /asistente/estado-urgencias: contrato, seguridad, errores y modo degradado. */
class EstadoUrgenciasApiTests extends AbstractIntegrationTest {

	private static final String RUTA = "/asistente/estado-urgencias";
	private static final Instant T0 = Instant.parse("2026-11-04T15:00:00Z");

	@Autowired
	Environment entorno;

	private void registrar(NivelTriaje nivel, Instant cuando) {
		vista.aplicar(UUID.randomUUID(), cuando, new OperacionEstado.Registrar(UUID.randomUUID(), nivel, cuando)).block();
	}

	@Test
	void sinApiKeyEs401ConProblemDetail() {
		anonimo().get().uri(RUTA).exchange().expectStatus().isUnauthorized()
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(401)
				.jsonPath("$.type").isEqualTo("urn:vidacruz:asistente:problema:no-autenticado")
				.jsonPath("$.correlationId").exists();
	}

	@Test
	void conApiKeyInvalidaEs401() {
		anonimo().get().uri(RUTA).header("X-API-Key", "otra").exchange().expectStatus().isUnauthorized();
	}

	@Test
	void sinEventosDevuelveLosCincoNivelesEnCeroYUltimoEventoNulo() {
		api().get().uri(RUTA).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.totalActivos").isEqualTo(0)
				.jsonPath("$.porNivel.CRITICO").isEqualTo(0)
				.jsonPath("$.porNivel.URGENTE").isEqualTo(0)
				.jsonPath("$.porNivel.URGENTE_2").isEqualTo(0)
				.jsonPath("$.porNivel.NO_URGENTE").isEqualTo(0)
				.jsonPath("$.porNivel.NO_URGENTE_2").isEqualTo(0)
				.jsonPath("$.escalados").isEqualTo(0)
				.jsonPath("$.ultimoEventoEn").value(org.hamcrest.Matchers.nullValue())
				.jsonPath("$.consultadoEn").exists();
	}

	@Test
	void devuelveElEstadoConLaFormaDocumentada() {
		registrar(NivelTriaje.CRITICO, T0);
		registrar(NivelTriaje.URGENTE, T0.plusSeconds(5));
		registrar(NivelTriaje.URGENTE, T0.plusSeconds(10));
		UUID escalado = UUID.randomUUID();
		vista.aplicar(UUID.randomUUID(), T0, new OperacionEstado.Registrar(escalado, NivelTriaje.NO_URGENTE, T0)).block();
		vista.aplicar(UUID.randomUUID(), T0.plusSeconds(30), new OperacionEstado.Escalar(escalado)).block();

		api().get().uri(RUTA).header("X-Correlation-Id", "prueba-estado").exchange().expectStatus().isOk()
				.expectHeader().valueEquals("X-Correlation-Id", "prueba-estado")
				.expectBody()
				.jsonPath("$.totalActivos").isEqualTo(4)
				.jsonPath("$.porNivel.CRITICO").isEqualTo(1)
				.jsonPath("$.porNivel.URGENTE").isEqualTo(2)
				.jsonPath("$.porNivel.URGENTE_2").isEqualTo(0)
				.jsonPath("$.porNivel.NO_URGENTE").isEqualTo(1)
				.jsonPath("$.porNivel.NO_URGENTE_2").isEqualTo(0)
				.jsonPath("$.escalados").isEqualTo(1)
				// 15:00:30Z = 10:00:30 en Bogota (-05:00)
				.jsonPath("$.ultimoEventoEn").isEqualTo("2026-11-04T10:00:30-05:00")
				.jsonPath("$.consultadoEn").value(startsWith("20"));
	}

	@Test
	void conRedisCaidoResponde503ConProblemDetailYSeRecuperaDespues() {
		registrar(NivelTriaje.CRITICO, T0);
		var docker = DockerClientFactory.lazyClient();
		docker.pauseContainerCmd(Contenedores.redis().getContainerId()).exec();
		try {
			api().get().uri(RUTA).exchange().expectStatus().isEqualTo(503)
					.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
					.expectHeader().exists("Retry-After")
					.expectBody()
					.jsonPath("$.status").isEqualTo(503)
					.jsonPath("$.type").isEqualTo("urn:vidacruz:asistente:problema:estado-no-disponible")
					.jsonPath("$.detail").value(containsString("Redis"))
					.jsonPath("$.correlationId").exists();
		}
		finally {
			docker.unpauseContainerCmd(Contenedores.redis().getContainerId()).exec();
		}

		api().get().uri(RUTA).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.totalActivos").isEqualTo(1);
	}

	@Test
	void laReadinessIncluyeRedisPeroNoKafka() {
		String grupo = entorno.getProperty("management.endpoint.health.group.readiness.include");

		assertThat(grupo).contains("redis").doesNotContain("kafka");
		anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
		anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
	}

	@Test
	void lasMetricasDeNegocioEstanRegistradas() {
		registrar(NivelTriaje.URGENTE, T0);

		api().get().uri(RUTA).exchange().expectStatus().isOk();

		assertThat(meterRegistry.find("asistente.pacientes.activos").gauges()).hasSize(5);
		assertThat(meterRegistry.find("asistente.pacientes.activos").tag("nivel", "URGENTE").gauge().value()).isEqualTo(1.0);
	}

	@Test
	void ladocumentacionOpenApiEsPublicaYDescribeElEndpoint() {
		anonimo().get().uri("/v3/api-docs").exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.paths['/asistente/estado-urgencias'].get.summary").exists()
				.jsonPath("$.paths['/asistente/estado-urgencias'].get.responses.*~").value(hasSize(4))
				.jsonPath("$.components.securitySchemes.ApiKeyAuth.name").isEqualTo("X-API-Key")
				.jsonPath("$.components.securitySchemes.ApiKeyAuth.in").isEqualTo("header");
		anonimo().get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
	}

}
