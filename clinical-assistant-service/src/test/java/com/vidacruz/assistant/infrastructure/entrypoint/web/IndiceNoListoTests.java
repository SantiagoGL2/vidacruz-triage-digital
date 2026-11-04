package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
import com.vidacruz.assistant.support.ProveedorLlmSimulado;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * La indexacion NO tumba el arranque si el proveedor de embeddings falla: la aplicacion arranca, la consulta responde 503
 * claro, la readiness refleja el indice no listo (la liveness sigue OK), la vista de urgencias funciona y, cuando el
 * proveedor se recupera, la indexacion en segundo plano termina sola. Contexto propio con un indice distinto.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
		"assistant.rag.indice.nombre=asistente-protocolos-idx-arranque",
		"assistant.rag.indice.prefijo=asistente:protocolo-arranque:"})
class IndiceNoListoTests extends AbstractIntegrationTest {

	@BeforeAll
	static void proveedorDeEmbeddingsCaidoAlArrancar() {
		ProveedorLlmSimulado.reiniciar();
		// se registra ANTES de que arranque el contexto de esta clase (JUnit crea la instancia despues de los @BeforeAll)
		ProveedorLlmSimulado.servidor().stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_EMBEDDINGS)).atPriority(0)
				.willReturn(aResponse().withStatus(500).withBody("{\"error\":{\"message\":\"proveedor caido\"}}")));
	}

	@Override
	protected boolean esperarIndiceListo() {
		return false;
	}

	@Test
	void laAplicacionArrancaConElIndiceNoListoYSeRecuperaCuandoVuelveElProveedor() {
		// 1) arranco pese al fallo: consulta -> 503 claro; readiness DOWN; liveness UP; vista de urgencias disponible
		assertThat(estadoIndice.actual().listo()).isFalse();
		api().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?")).exchange()
				.expectStatus().isEqualTo(503)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader().valueEquals("Retry-After", "3")
				.expectBody()
				.jsonPath("$.type").isEqualTo("https://vidacruz.example/errores/indice-no-disponible")
				.jsonPath("$.detail").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.containsString("se está preparando"),
						org.hamcrest.Matchers.containsString("reintenta automáticamente")));
		anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503)
				.expectBody().jsonPath("$.status").isEqualTo("DOWN");
		anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
		api().get().uri("/asistente/estado-urgencias").exchange().expectStatus().isOk();
		assertThat(estadoIndice.actual().estado()).isNotEqualTo(EstadoIndexacion.LISTO);

		// 2) el proveedor se recupera: la indexacion en segundo plano (con backoff) termina sola
		ProveedorLlmSimulado.reiniciar();     // quita el stub de fallo (prioridad 0) y deja el proveedor sano
		await().atMost(Duration.ofSeconds(60)).until(() -> estadoIndice.actual().listo());

		anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
		api().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-001");
		assertThat(estadoIndice.actual().fragmentosIndexados()).isEqualTo(7);
	}

}
