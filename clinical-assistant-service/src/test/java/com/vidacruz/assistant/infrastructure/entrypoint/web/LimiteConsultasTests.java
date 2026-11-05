package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Rate limiting de {@code POST /asistente/consulta} por API key: cada consulta cuesta una llamada al proveedor de IA.
 * Contexto propio con un limite bajo (3 consultas por 30 s) y grupo de consumidores propio (dos contextos vivos con el
 * mismo group-id se repartirian las particiones de Kafka); {@code @DirtiesContext} lo cierra al terminar.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
		"assistant.rate-limit.peticiones=3",
		"assistant.rate-limit.periodo=PT30S",
		"assistant.kafka.group-id=asistente-limite-test"})
class LimiteConsultasTests extends AbstractIntegrationTest {

	private static final Map<String, String> PREGUNTA =
			Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?");

	private WebTestClient.ResponseSpec consultar() {
		return api().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON).bodyValue(PREGUNTA).exchange();
	}

	/**
	 * Un solo escenario secuencial: el cupo es del limiter de la API key y se comparte entre todos los metodos del contexto,
	 * asi que separarlos haria que el resultado dependiera del orden de ejecucion.
	 */
	@Test
	void superadoElLimiteLaConsultaResponde429SinAfectarAOtrasRutasNiAlaAutenticacion() {
		double antes = contador("asistente.consultas.limitadas");

		// 1) Dentro del cupo (3 consultas): 200.
		for (int i = 0; i < 3; i++) {
			consultar().expectStatus().isOk();
		}

		// 2) Superado: 429 en ProblemDetail (catalogo de errores comun) con Retry-After.
		consultar().expectStatus().isEqualTo(429)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader().valueEquals("Retry-After", "30")
				.expectHeader().exists("X-Correlation-Id")
				.expectBody()
				.jsonPath("$.type").isEqualTo("https://vidacruz.example/errores/demasiadas-peticiones")
				.jsonPath("$.title").isEqualTo("Demasiadas peticiones")
				.jsonPath("$.status").isEqualTo(429)
				.jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.containsString("3 consultas"), org.hamcrest.Matchers.containsString("30 segundos")))
				.jsonPath("$.instance").isEqualTo("/asistente/consulta")
				.jsonPath("$.correlationId").isNotEmpty();

		// 3) Cada rechazo se cuenta en la metrica.
		consultar().expectStatus().isEqualTo(429);
		assertThat(contador("asistente.consultas.limitadas")).isEqualTo(antes + 2);

		// 4) El limite solo afecta a la consulta: la vista de urgencias sigue respondiendo.
		for (int i = 0; i < 5; i++) {
			api().get().uri("/asistente/estado-urgencias").exchange().expectStatus().isOk();
		}

		// 5) Sin API key sigue siendo 401 (la autenticacion va antes que el limite y no consume cupo).
		anonimo().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON).bodyValue(PREGUNTA).exchange()
				.expectStatus().isUnauthorized();
	}

}
