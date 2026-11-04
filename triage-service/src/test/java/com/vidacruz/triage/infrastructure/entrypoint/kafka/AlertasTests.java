package com.vidacruz.triage.infrastructure.entrypoint.kafka;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.AlertaResponse;
import com.vidacruz.triage.support.AbstractIntegrationTest;
import com.vidacruz.triage.support.ConsumidorDePrueba;
import com.vidacruz.triage.support.Contenedores;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * RF-06: del topic al cliente SSE y al webhook (WireMock), idempotencia por eventId, aislamiento entre canales y DLT.
 * Se publican eventos escritos a mano en {@code triaje.eventos}; el consumidor usa un group propio de esta clase.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AlertasTests extends AbstractIntegrationTest {

	static final String TOPIC = "triaje.eventos";
	static final WireMockServer WEBHOOK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

	static {
		WEBHOOK.start();
	}

	@DynamicPropertySource
	static void alertas(DynamicPropertyRegistry registry) {
		registry.add("triage.alertas.consumidor.habilitado", () -> "true");
		registry.add("triage.alertas.consumidor.group-id", () -> "alertas-test-" + UUID.randomUUID());
		registry.add("triage.alertas.webhook.url", () -> "http://localhost:" + WEBHOOK.port() + "/alertas");
	}

	@AfterAll
	static void cerrarWebhook() {
		WEBHOOK.stop();
	}

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	MeterRegistry meterRegistry;

	@BeforeEach
	void webhookOk() {
		WEBHOOK.resetAll();
		WEBHOOK.stubFor(post(urlEqualTo("/alertas")).willReturn(WireMock.ok()));
	}

	// ------------------------------------------------------------------ utilidades

	private void publicar(String tipo, UUID eventId, UUID pacienteId) throws Exception {
		Instant registro = Instant.now().minusSeconds(900);
		var payload = new EventoTriaje.Payload(pacienteId, "CRITICO", 1, "REGISTRADO", "REGISTRADO", registro, 5, true,
				null, Instant.now());
		var evento = new EventoTriaje(eventId, tipo, Instant.now(), 1, "corr-alertas", payload);
		kafka.send(TOPIC, pacienteId.toString(), objectMapper.writeValueAsString(evento)).get();
	}

	private void publicarEscalado(UUID pacienteId) throws Exception {
		publicar("paciente_escalado", UUID.randomUUID(), pacienteId);
	}

	/**
	 * Conecta un cliente SSE y ESPERA a que el servidor ya lo tenga suscrito (llega un keep-alive) antes de
	 * devolverlo: el sink de alertas no guarda nada para quien aun no esta suscrito. Devuelve solo las alertas de
	 * los pacientes indicados. El cuerpo se suscribe una sola vez y se repite a quien lo consuma despues.
	 */
	private Flux<AlertaResponse> sse(UUID... pacientes) {
		var ids = java.util.Set.of(pacientes);
		Flux<ServerSentEvent<AlertaResponse>> eventos = api().get().uri("/triage/alertas/stream")
				.accept(MediaType.TEXT_EVENT_STREAM).exchange()
				.expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<AlertaResponse>>() { })
				.getResponseBody()
				.replay().autoConnect(0);          // una sola suscripcion real al cuerpo; los demas reciben la repeticion
		eventos.filter(e -> "keep-alive".equals(e.comment())).blockFirst(Duration.ofSeconds(15));
		return eventos
				.filter(e -> e.data() != null)
				.map(ServerSentEvent::data)
				.filter(a -> ids.contains(a.pacienteId()));
	}

	// ------------------------------------------------------------------ SSE

	@Test
	void unEventoEscaladoLlegaAUnClienteSseConElContenidoEsperado() throws Exception {
		UUID paciente = UUID.randomUUID();

		StepVerifier.create(sse(paciente))
				.then(() -> unchecked(() -> publicarEscalado(paciente)))
				.assertNext(a -> {
					assertThat(a.pacienteId()).isEqualTo(paciente);
					assertThat(a.nivel()).isEqualTo("CRITICO");
					assertThat(a.nivelNumero()).isEqualTo(1);
					assertThat(a.slaMinutos()).isEqualTo(5);
					assertThat(a.minutosEspera()).isGreaterThanOrEqualTo(14);          // registrado 15 min antes del evento
					assertThat(a.fechaRegistro()).isNotNull();
					assertThat(a.fechaEscalamiento()).isNotNull();
					assertThat(a.correlationId()).isEqualTo("corr-alertas");
				})
				.thenCancel()
				.verify(Duration.ofSeconds(60));
	}

	@Test
	void laAlertaNoLlevaElNombreDelPaciente() throws Exception {
		UUID paciente = UUID.randomUUID();

		var crudo = api().get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
				.expectStatus().isOk().returnResult(String.class).getResponseBody()
				.filter(linea -> linea.contains(paciente.toString()));

		StepVerifier.create(crudo)
				.then(() -> unchecked(() -> publicarEscalado(paciente)))
				.assertNext(linea -> assertThat(linea).contains("pacienteId").doesNotContainIgnoringCase("nombre"))
				.thenCancel()
				.verify(Duration.ofSeconds(60));
	}

	@Test
	void variosClientesConectadosRecibenTodasLasAlertas() throws Exception {
		UUID paciente = UUID.randomUUID();
		Flux<AlertaResponse> a = sse(paciente);
		Flux<AlertaResponse> b = sse(paciente);

		StepVerifier.create(Flux.merge(a.take(1), b.take(1)))
				.then(() -> unchecked(() -> publicarEscalado(paciente)))
				.expectNextCount(2)
				.expectComplete().verify(Duration.ofSeconds(60));
	}

	@Test
	void elSseEstaProtegidoConApiKey() {
		anonimo().get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
				.expectStatus().isUnauthorized();
	}

	@Test
	void elSseEmiteKeepAliveAunSinAlertas() {
		var comentarios = api().get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
				.expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<AlertaResponse>>() { })
				.getResponseBody()
				.filter(e -> "keep-alive".equals(e.comment()));

		StepVerifier.create(comentarios.take(2)).expectNextCount(2).expectComplete().verify(Duration.ofSeconds(30));
	}

	// ------------------------------------------------------------------ webhook

	@Test
	void elWebhookSeInvocaConLaAlertaSinNombre() throws Exception {
		UUID paciente = UUID.randomUUID();

		publicarEscalado(paciente);

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
				WEBHOOK.verify(1, postRequestedFor(urlEqualTo("/alertas"))
						.withRequestBody(matchingJsonPath("$.pacienteId", equalTo(paciente.toString())))
						.withRequestBody(matchingJsonPath("$.nivel", equalTo("CRITICO")))
						.withRequestBody(matchingJsonPath("$.slaMinutos", equalTo("5")))));
		assertThat(WEBHOOK.getAllServeEvents().stream().map(e -> e.getRequest().getBodyAsString())
				.filter(cuerpo -> cuerpo.contains(paciente.toString())).findFirst().orElseThrow())
				.doesNotContainIgnoringCase("nombre");
	}

	@Test
	void siElWebhookEstaCaidoElSseIgualRecibeLaAlertaYSeCuentaElFallo() throws Exception {
		WEBHOOK.resetAll();
		WEBHOOK.stubFor(post(urlEqualTo("/alertas")).willReturn(WireMock.serverError()));
		double fallosAntes = meterRegistry.counter("triage.alertas.fallos", "canal", "webhook").count();
		UUID paciente = UUID.randomUUID();

		StepVerifier.create(sse(paciente))
				.then(() -> unchecked(() -> publicarEscalado(paciente)))
				.assertNext(a -> assertThat(a.pacienteId()).isEqualTo(paciente))
				.thenCancel()
				.verify(Duration.ofSeconds(60));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(meterRegistry.counter("triage.alertas.fallos", "canal", "webhook").count()).isGreaterThan(fallosAntes);
			WEBHOOK.verify(WireMock.moreThanOrExactly(2), postRequestedFor(urlEqualTo("/alertas")));   // reintento
		});
	}

	// ------------------------------------------------------------------ idempotencia y filtrado

	@Test
	void unEventoDuplicadoNoGeneraDosAlertas() throws Exception {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID mismoEvento = UUID.randomUUID();

		// A, A (mismo eventId) y luego B: si el duplicado generara una segunda alerta, antes de B llegaria otra de A.
		StepVerifier.create(sse(a, b))
				.then(() -> unchecked(() -> {
					publicar("paciente_escalado", mismoEvento, a);
					publicar("paciente_escalado", mismoEvento, a);
					publicarEscalado(b);
				}))
				.assertNext(x -> assertThat(x.pacienteId()).isEqualTo(a))
				.assertNext(x -> assertThat(x.pacienteId()).isEqualTo(b))
				.thenCancel()
				.verify(Duration.ofSeconds(60));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
				WEBHOOK.verify(1, postRequestedFor(urlEqualTo("/alertas"))
						.withRequestBody(matchingJsonPath("$.pacienteId", equalTo(a.toString())))));
	}

	@Test
	void soloSeProcesaPacienteEscaladoLosDemasTiposSeIgnoran() throws Exception {
		UUID ignorado = UUID.randomUUID();
		UUID escalado = UUID.randomUUID();

		StepVerifier.create(sse(ignorado, escalado))
				.then(() -> unchecked(() -> {
					publicar("paciente_registrado", UUID.randomUUID(), ignorado);
					publicar("paciente_en_consulta", UUID.randomUUID(), ignorado);
					publicar("paciente_alta", UUID.randomUUID(), ignorado);
					publicarEscalado(escalado);
				}))
				.assertNext(x -> assertThat(x.pacienteId()).isEqualTo(escalado))
				.thenCancel()
				.verify(Duration.ofSeconds(60));
	}

	// ------------------------------------------------------------------ DLT

	@Test
	void unMensajeInvalidoVaDirectoALaDlt() throws Exception {
		String basura = "esto-no-es-json-" + UUID.randomUUID();
		kafka.send(TOPIC, "clave-invalida", basura).get();

		try (var consumidor = new ConsumidorDePrueba(Contenedores.kafka().getBootstrapServers(), TOPIC + ".DLT")) {
			var enDlt = consumidor.leerHasta(Duration.ofSeconds(45), leidos -> leidos.stream().anyMatch(r -> basura.equals(r.value())))
					.stream().filter(r -> basura.equals(r.value())).findFirst();

			assertThat(enDlt).isPresent();
			assertThat(ConsumidorDePrueba.cabecera(enDlt.get(), "kafka_dlt-original-topic")).isEqualTo(TOPIC);
			assertThat(ConsumidorDePrueba.cabecera(enDlt.get(), "kafka_dlt-exception-fqcn")).isNotBlank();
		}
	}

	@Test
	void unEventoBienFormadoPeroIncompletoTambienVaALaDlt() throws Exception {
		String incompleto = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"paciente_escalado\","
				+ "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"version\":1,\"payload\":{\"pacienteId\":null}}";
		kafka.send(TOPIC, "clave-incompleta", incompleto).get();

		try (var consumidor = new ConsumidorDePrueba(Contenedores.kafka().getBootstrapServers(), TOPIC + ".DLT")) {
			var enDlt = consumidor.leerHasta(Duration.ofSeconds(45), leidos -> leidos.stream().anyMatch(r -> incompleto.equals(r.value())));

			assertThat(enDlt).anyMatch(r -> incompleto.equals(r.value()));
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void unchecked(ThrowingRunnable r) {
		try {
			r.run();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@FunctionalInterface
	interface ThrowingRunnable {

		void run() throws Exception;

	}

}
