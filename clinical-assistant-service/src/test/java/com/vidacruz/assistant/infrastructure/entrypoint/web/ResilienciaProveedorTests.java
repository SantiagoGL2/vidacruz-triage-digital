package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.ProveedorLlmSimulado;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Resiliencia y modo degradado: retry solo ante transitorios, circuit breaker, timeout y 503 con mensaje claro, sin
 * afectar a la vista de urgencias ni a la readiness.
 */
class ResilienciaProveedorTests extends AbstractIntegrationTest {

	static final String PREGUNTA_1 = "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?";
	static final String MENSAJE = "El asistente clínico no está disponible temporalmente; consulte los protocolos impresos";

	private WebTestClient.ResponseSpec consultar() {
		return api().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("pregunta", PREGUNTA_1)).exchange();
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(int estado) {
		return aResponse().withStatus(estado).withHeader("Content-Type", "application/json")
				.withBody("{\"error\":{\"message\":\"fallo simulado " + estado + "\",\"type\":\"server_error\"}}");
	}

	private static void chatSiempre(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder respuesta) {
		ProveedorLlmSimulado.servidor().stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).willReturn(respuesta));
	}

	private void esperar503Degradado(WebTestClient.ResponseSpec r) {
		r.expectStatus().isEqualTo(503)
				.expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.expectHeader().valueEquals("Retry-After", "7")
				.expectBody()
				.jsonPath("$.status").isEqualTo(503)
				.jsonPath("$.type").isEqualTo("https://vidacruz.example/errores/asistente-no-disponible")
				.jsonPath("$.detail").isEqualTo(MENSAJE)
				.jsonPath("$.correlationId").exists();
	}

	// ------------------------------------------------------------------ retry

	@Test
	void unProveedorQueDevuelve500DosVecesYLuego200ResponseBienPorElRetry() {
		var s = ProveedorLlmSimulado.servidor();
		s.stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).inScenario("retry")
				.whenScenarioStateIs(Scenario.STARTED).willReturn(error(500)).willSetStateTo("fallo-1"));
		s.stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).inScenario("retry")
				.whenScenarioStateIs("fallo-1").willReturn(error(500)).willSetStateTo("fallo-2"));
		s.stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).inScenario("retry")
				.whenScenarioStateIs("fallo-2").willReturn(ProveedorLlmSimulado.chat(
						ProveedorLlmSimulado.respondida("Vigilar signos de encefalopatía.", "PROTO-001", "Medidas iniciales"))));

		consultar().expectStatus().isOk().expectBody()
				.jsonPath("$.respuesta").isEqualTo("Vigilar signos de encefalopatía.")
				.jsonPath("$.fuentes[0].protocolo").isEqualTo("PROTO-001");

		assertThat(ProveedorLlmSimulado.llamadasChat()).isEqualTo(3);
		assertThat(circuitos.circuitBreaker("llm-chat").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void el429SeReintenta() {
		var s = ProveedorLlmSimulado.servidor();
		s.stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).inScenario("limite")
				.whenScenarioStateIs(Scenario.STARTED).willReturn(error(429)).willSetStateTo("otra"));
		s.stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_CHAT)).atPriority(0).inScenario("limite")
				.whenScenarioStateIs("otra").willReturn(ProveedorLlmSimulado.chat(
						ProveedorLlmSimulado.respondida("Texto.", "PROTO-001", "Medidas iniciales"))));

		consultar().expectStatus().isOk();

		assertThat(ProveedorLlmSimulado.llamadasChat()).isEqualTo(2);
	}

	@Test
	void unErrorDeConfiguracion401NoSeReintentaYDegradaA503() {
		chatSiempre(error(401));

		esperar503Degradado(consultar());

		assertThat(ProveedorLlmSimulado.llamadasChat()).as("un 4xx de configuracion no se reintenta").isEqualTo(1);
	}

	// ------------------------------------------------------------------ proveedor caido y circuit breaker

	@Test
	void conElProveedorCaidoResponde503ConMensajeClaroYElCircuitBreakerSeAbre() {
		chatSiempre(error(500));

		esperar503Degradado(consultar());                       // 3 intentos fallidos (max-attempts = 3)
		assertThat(ProveedorLlmSimulado.llamadasChat()).isEqualTo(3);
		esperar503Degradado(consultar());                       // el 4.º fallo abre el circuito; el reintento ya no llama
		assertThat(circuitos.circuitBreaker("llm-chat").getState()).isEqualTo(CircuitBreaker.State.OPEN);
		int llamadasConElCircuitoAbierto = ProveedorLlmSimulado.llamadasChat();

		esperar503Degradado(consultar());                       // circuito abierto: ni siquiera se llama al proveedor
		esperar503Degradado(consultar());

		assertThat(ProveedorLlmSimulado.llamadasChat()).as("con el circuito abierto no se llama al proveedor")
				.isEqualTo(llamadasConElCircuitoAbierto);
		// el circuito de embeddings es independiente: sigue cerrado
		assertThat(circuitos.circuitBreaker("llm-embedding").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(contador("asistente.consultas", "resultado", "degradado")).isGreaterThanOrEqualTo(4);
	}

	@Test
	void conElProveedorCaidoLaVistaDeUrgenciasYLaReadinessSiguenFuncionando() {
		chatSiempre(error(500));
		consultar();
		consultar();
		assertThat(circuitos.circuitBreaker("llm-chat").getState()).isEqualTo(CircuitBreaker.State.OPEN);

		api().get().uri("/asistente/estado-urgencias").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.totalActivos").isEqualTo(0);
		anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.status").isEqualTo("UP");
		anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
	}

	@Test
	void despuesDeLaEsperaElCircuitoSePruebaYSeRecupera() throws Exception {
		chatSiempre(error(500));
		consultar();
		consultar();
		assertThat(circuitos.circuitBreaker("llm-chat").getState()).isEqualTo(CircuitBreaker.State.OPEN);

		circuitos.circuitBreaker("llm-chat").transitionToHalfOpenState();
		ProveedorLlmSimulado.reiniciar();    // el proveedor "se recupera"

		consultar().expectStatus().isOk();
	}

	// ------------------------------------------------------------------ timeout

	@Test
	void unTimeoutDelProveedorResponde503() {
		chatSiempre(ProveedorLlmSimulado.chat("{\"respondida\":false,\"respuesta\":\"\",\"citas\":[]}").withFixedDelay(4000));

		long inicio = System.nanoTime();
		esperar503Degradado(consultar());
		long segundos = (System.nanoTime() - inicio) / 1_000_000_000L;

		assertThat(segundos).as("responde por el time limiter, no esperando al proveedor indefinidamente").isLessThan(20);
	}

	// ------------------------------------------------------------------ embeddings

	@Test
	void siElProveedorDeEmbeddingsFallaLaConsultaTambienDegradaA503SinLlamarAlChat() {
		ProveedorLlmSimulado.servidor().stubFor(post(urlEqualTo(ProveedorLlmSimulado.RUTA_EMBEDDINGS)).atPriority(0)
				.willReturn(error(500)));

		consultar().expectStatus().isEqualTo(503).expectBody()
				.jsonPath("$.detail").isEqualTo(MENSAJE)
				.jsonPath("$.operacion").isEqualTo("embedding");

		assertThat(ProveedorLlmSimulado.llamadasChat()).isZero();
		assertThat(circuitos.circuitBreaker("llm-chat").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	// ------------------------------------------------------------------ la validacion no cuenta como falla del circuito

	@Test
	void lasFallasDeValidacionNoCuentanComoFallasDelCircuitBreaker() {
		chatSiempre(ProveedorLlmSimulado.chat("{\"respondida\":true,\"respuesta\":\"Inventado.\",\"citas\":[{\"protocolo\":\"PROTO-009\",\"seccion\":\"X\"}]}"));

		for (int i = 0; i < 12; i++) {
			consultar().expectStatus().isOk().expectBody().jsonPath("$.fuentes").isEmpty();
		}
		chatSiempre(ProveedorLlmSimulado.chat("salida que no es JSON"));
		for (int i = 0; i < 12; i++) {
			consultar().expectStatus().isOk().expectBody().jsonPath("$.fuentes").isEmpty();
		}

		CircuitBreaker cb = circuitos.circuitBreaker("llm-chat");
		assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
	}

	// ------------------------------------------------------------------ observabilidad

	@Test
	void seRegistranLatenciaYTokensDelProveedor() {
		consultar().expectStatus().isOk();

		var timerChat = meterRegistry.find("asistente.llm.latencia").tags("operacion", "chat", "resultado", "ok").timer();
		var timerEmbedding = meterRegistry.find("asistente.llm.latencia").tags("operacion", "embedding", "resultado", "ok").timer();
		assertThat(timerChat).isNotNull();
		assertThat(timerChat.count()).isGreaterThanOrEqualTo(1);
		assertThat(timerEmbedding).isNotNull();
		assertThat(contador("asistente.llm.tokens", "tipo", "prompt")).isGreaterThanOrEqualTo(120);
		assertThat(contador("asistente.llm.tokens", "tipo", "completion")).isGreaterThanOrEqualTo(40);
		assertThat(contador("asistente.consultas", "resultado", "respondida")).isGreaterThanOrEqualTo(1);
	}

	@Test
	void laLatenciaDeUnaLlamadaFallidaSeRegistraConResultadoError() {
		chatSiempre(error(401));

		consultar().expectStatus().isEqualTo(503);

		var timer = meterRegistry.find("asistente.llm.latencia").tags("operacion", "chat", "resultado", "error").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void elEstadoDeLosCircuitBreakersSeExponeEnActuatorYEnPrometheus() {
		api().get().uri("/actuator/circuitbreakers").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.circuitBreakers['llm-chat'].state").isEqualTo("CLOSED")
				.jsonPath("$.circuitBreakers['llm-embedding'].state").isEqualTo("CLOSED");

		api().get().uri("/actuator/prometheus").exchange().expectStatus().isOk().expectBody(String.class)
				.value(texto -> {
					assertThat(texto).contains("resilience4j_circuitbreaker_state{").contains("name=\"llm-chat\"")
							.contains("name=\"llm-embedding\"").contains("asistente_llm_latencia_seconds");
				});
	}

	@SuppressWarnings("unused")
	private static String sinUso() {
		return containsString("x").toString();
	}

}
