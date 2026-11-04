package com.vidacruz.triage.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.domain.exception.ConflictoConcurrenciaException;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

/** RF-01, RF-02 y RN-04 de punta a punta: HTTP -> casos de uso -> PostgreSQL real (Testcontainers). */
class PacienteApiTests extends AbstractIntegrationTest {

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	PacienteRepositoryPort pacientes;

	@Autowired
	MeterRegistry meterRegistry;

	// ------------------------------------------------------------------ helpers

	static Map<String, Object> cuerpoValido() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("nombreCompleto", "Paciente Prueba");
		m.put("edad", 40);
		m.put("frecuenciaCardiaca", 72);
		m.put("presionSistolica", 120);
		m.put("presionDiastolica", 80);
		m.put("spo2", 99);
		m.put("temperatura", 36.5);
		m.put("dolor", 2);
		m.put("gcs", 15);
		m.put("quejaPrincipal", "Herida por cortar");
		return m;
	}

	static Map<String, Object> cuerpoCon(String campo, Object valor) {
		Map<String, Object> m = cuerpoValido();
		m.put(campo, valor);
		return m;
	}

	UUID crear(Map<String, Object> cuerpo) {
		Map<String, Object> r = api().post().uri("/triage/pacientes").bodyValue(cuerpo).exchange()
				.expectStatus().isCreated()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult().getResponseBody();
		return UUID.fromString((String) r.get("id"));
	}

	WebTestClient.ResponseSpec patch(UUID id, String estado) {
		return api().patch().uri("/triage/pacientes/{id}/estado", id).bodyValue(Map.of("estado", estado)).exchange();
	}

	List<JsonNode> eventosOutbox(UUID pacienteId) {
		return db.sql("SELECT id, payload::text AS payload FROM outbox_eventos WHERE aggregate_id = :id ORDER BY created_at, id")
				.bind("id", pacienteId)
				.map(fila -> {
					try {
						var nodo = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fila.get("payload", String.class));
						nodo.put("_filaId", fila.get("id", UUID.class).toString());
						return (JsonNode) nodo;
					}
					catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).all().collectList().block();
	}

	// ------------------------------------------------------------------ RF-01: registro y clasificacion

	@ParameterizedTest(name = "{7} -> {8}")
	@CsvSource({
			"130, 180, 110, 97, 36.8, 8, 15, 'Dolor torácico', CRITICO, 1, 5",
			"95, 150, 95, 91, 37.0, 4, 15, 'Dificultad para respirar', URGENTE, 2, 10",
			"88, 130, 85, 98, 38.2, 6, 15, Fiebre, URGENTE_2, 3, 30",
			"72, 120, 80, 99, 36.5, 2, 15, 'Herida por cortar', NO_URGENTE, 4, 60",
			"70, 115, 75, 99, 36.4, 0, 15, 'Renovación de recetario', NO_URGENTE_2, 5, 120",
			"35, 80, 50, 85, 35.9, 0, 6, Inconsciente, CRITICO, 1, 5"})
	void registraLosPacientesDeReferenciaConSuNivelYSla(int fc, int sistolica, int diastolica, int spo2, double temperatura,
			int dolor, int gcs, String queja, String nivel, int numero, int sla) {
		Map<String, Object> cuerpo = cuerpoValido();
		cuerpo.put("frecuenciaCardiaca", fc);
		cuerpo.put("presionSistolica", sistolica);
		cuerpo.put("presionDiastolica", diastolica);
		cuerpo.put("spo2", spo2);
		cuerpo.put("temperatura", temperatura);
		cuerpo.put("dolor", dolor);
		cuerpo.put("gcs", gcs);
		cuerpo.put("quejaPrincipal", queja);

		api().post().uri("/triage/pacientes").bodyValue(cuerpo).exchange()
				.expectStatus().isCreated()
				.expectHeader().valueMatches("Location", "/triage/pacientes/[0-9a-f-]{36}")
				.expectBody()
				.jsonPath("$.id").isNotEmpty()
				.jsonPath("$.nivel").isEqualTo(nivel)
				.jsonPath("$.nivelNumero").isEqualTo(numero)
				.jsonPath("$.slaMinutos").isEqualTo(sla)
				.jsonPath("$.estado").isEqualTo("REGISTRADO")
				.jsonPath("$.fechaRegistro").value(f -> assertThat(Instant.parse((String) f)).isNotNull());
	}

	@Test
	void locationApuntaAlPacienteCreadoYElGetLoDevuelve() {
		var resultado = api().post().uri("/triage/pacientes").bodyValue(cuerpoValido()).exchange()
				.expectStatus().isCreated().returnResult(String.class);
		String location = resultado.getResponseHeaders().getFirst("Location");

		api().get().uri(location).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.estado").isEqualTo("REGISTRADO");
	}

	@Test
	void incrementaElContadorDeClasificadosConElTagDelNivel() {
		var contador = meterRegistry.counter("triage.pacientes.clasificados", "nivel", "NO_URGENTE");
		double antes = contador.count();

		crear(cuerpoValido());

		assertThat(contador.count()).isEqualTo(antes + 1);
	}

	// ------------------------------------------------------------------ RF-01: validaciones (400)

	static Stream<Arguments> valoresFueraDeRango() {
		return Stream.of(
				Arguments.of("frecuenciaCardiaca", 19), Arguments.of("frecuenciaCardiaca", 251),
				Arguments.of("presionSistolica", 59), Arguments.of("presionSistolica", 261),
				Arguments.of("presionDiastolica", 29), Arguments.of("presionDiastolica", 161),
				Arguments.of("spo2", 69), Arguments.of("spo2", 101),
				Arguments.of("temperatura", 33.9), Arguments.of("temperatura", 42.1),
				Arguments.of("dolor", -1), Arguments.of("dolor", 11),
				Arguments.of("gcs", 2), Arguments.of("gcs", 16),
				Arguments.of("edad", -1), Arguments.of("edad", 121),
				Arguments.of("nombreCompleto", "ab"), Arguments.of("nombreCompleto", "x".repeat(101)),
				Arguments.of("quejaPrincipal", "   "), Arguments.of("quejaPrincipal", "q".repeat(501)));
	}

	@ParameterizedTest(name = "{0}={1}")
	@MethodSource("valoresFueraDeRango")
	void rechazaValoresFueraDeRangoIndicandoElCampo(String campo, Object valor) {
		long pacientesAntes = contar("pacientes");

		api().post().uri("/triage/pacientes").bodyValue(cuerpoCon(campo, valor)).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(400)
				.jsonPath("$.errores[*].campo").value(hasItem(campo))
				.jsonPath("$.errores[?(@.campo=='" + campo + "')].mensaje").isNotEmpty()
				.jsonPath("$.correlationId").isNotEmpty();

		assertThat(contar("pacientes")).isEqualTo(pacientesAntes);
	}

	@Test
	void diastolicaIgualOMayorQueSistolicaEsInvalida() {
		Map<String, Object> cuerpo = cuerpoCon("presionSistolica", 100);
		cuerpo.put("presionDiastolica", 100);

		api().post().uri("/triage/pacientes").bodyValue(cuerpo).exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem("presionDiastolica"));
	}

	@Test
	void variosErroresSeReportanJuntosConValorRecibido() {
		Map<String, Object> cuerpo = cuerpoCon("frecuenciaCardiaca", 5);
		cuerpo.put("spo2", 50);
		cuerpo.put("edad", 200);

		api().post().uri("/triage/pacientes").bodyValue(cuerpo).exchange()
				.expectStatus().isBadRequest()
				.expectBody()
				.jsonPath("$.errores.length()").isEqualTo(3)
				.jsonPath("$.errores[*].campo").value(hasItems("frecuenciaCardiaca", "spo2", "edad"))
				.jsonPath("$.errores[?(@.campo=='spo2')].valor").value(hasItem(50));
	}

	@ParameterizedTest(name = "falta {0}")
	@CsvSource({"nombreCompleto", "edad", "frecuenciaCardiaca", "presionSistolica", "presionDiastolica", "spo2",
			"temperatura", "dolor", "gcs", "quejaPrincipal"})
	void campoFaltanteEsUn400ConElCampo(String campo) {
		Map<String, Object> cuerpo = cuerpoValido();
		cuerpo.remove(campo);

		api().post().uri("/triage/pacientes").bodyValue(cuerpo).exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem(campo));
	}

	@ParameterizedTest(name = "{0}={1}")
	@MethodSource("tiposInvalidos")
	void tipoInvalidoEsUn400ConElCampo(String campo, Object valor) {
		api().post().uri("/triage/pacientes").bodyValue(cuerpoCon(campo, valor)).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem(campo));
	}

	static Stream<Arguments> tiposInvalidos() {
		return Stream.of(
				Arguments.of("edad", "abc"),
				Arguments.of("edad", "40"),         // un string numerico tampoco se acepta en un campo entero
				Arguments.of("frecuenciaCardiaca", 72.5),   // no se trunca en silencio
				Arguments.of("temperatura", "caliente"),
				Arguments.of("nombreCompleto", 12345));
	}

	@Test
	void jsonMalformadoEsUn400() {
		api().post().uri("/triage/pacientes").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"nombreCompleto\": ")
				.exchange()
				.expectStatus().isBadRequest()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.type").isEqualTo("urn:vidacruz:triage:problema:json-malformado")
				.jsonPath("$.correlationId").isNotEmpty();
	}

	@Test
	void cuerpoAusenteEsUn400() {
		api().post().uri("/triage/pacientes").exchange().expectStatus().isBadRequest();
	}

	// ------------------------------------------------------------------ RF-02: transiciones

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({"REGISTRADO, EN_CONSULTA", "REGISTRADO, ALTA", "EN_CONSULTA, ALTA"})
	void transicionesValidasDevuelven200(String desde, String hacia) {
		UUID id = crear(cuerpoValido());
		if (!desde.equals("REGISTRADO")) {
			patch(id, desde).expectStatus().isOk();
		}

		patch(id, hacia).expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo(id.toString())
				.jsonPath("$.estado").isEqualTo(hacia);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({"ALTA, EN_CONSULTA", "ALTA, REGISTRADO", "ALTA, ALTA", "EN_CONSULTA, REGISTRADO", "EN_CONSULTA, EN_CONSULTA",
			"REGISTRADO, REGISTRADO"})
	void transicionesInvalidasDevuelven409ConMensaje(String desde, String hacia) {
		UUID id = crear(cuerpoValido());
		if (desde.equals("EN_CONSULTA") || desde.equals("ALTA")) {
			patch(id, "EN_CONSULTA").expectStatus().isOk();
		}
		if (desde.equals("ALTA")) {
			patch(id, "ALTA").expectStatus().isOk();
		}
		List<String> auditoriaAntes = tiposAuditoria(id);
		List<String> outboxAntes = tiposOutbox(id);

		patch(id, hacia).expectStatus().isEqualTo(409)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(409)
				.jsonPath("$.detail").value(containsString(desde))
				.jsonPath("$.estadoActual").isEqualTo(desde)
				.jsonPath("$.estadoSolicitado").isEqualTo(hacia);

		assertThat(tiposAuditoria(id)).hasSameSizeAs(auditoriaAntes);
		assertThat(tiposOutbox(id)).hasSameSizeAs(outboxAntes);
	}

	@Test
	void estadoDesconocidoEsUn400() {
		UUID id = crear(cuerpoValido());

		patch(id, "VOLANDO").expectStatus().isBadRequest()
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem("estado"));
	}

	@Test
	void patchSinEstadoEsUn400() {
		UUID id = crear(cuerpoValido());

		api().patch().uri("/triage/pacientes/{id}/estado", id).bodyValue(Map.of()).exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem("estado"));
	}

	// ------------------------------------------------------------------ GET y 404

	@Test
	void getDevuelveDatosCompletosFlagsYTiempoEnElEstado() {
		UUID id = crear(cuerpoValido());

		api().get().uri("/triage/pacientes/{id}", id).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.id").isEqualTo(id.toString())
				.jsonPath("$.nombreCompleto").isEqualTo("Paciente Prueba")
				.jsonPath("$.edad").isEqualTo(40)
				.jsonPath("$.signosVitales.frecuenciaCardiaca").isEqualTo(72)
				.jsonPath("$.signosVitales.temperatura").isEqualTo(36.5)
				.jsonPath("$.quejaPrincipal").isEqualTo("Herida por cortar")
				.jsonPath("$.nivel").isEqualTo("NO_URGENTE")
				.jsonPath("$.slaMinutos").isEqualTo(60)
				.jsonPath("$.estado").isEqualTo("REGISTRADO")
				.jsonPath("$.escalado").isEqualTo(false)
				.jsonPath("$.fechaEscalamiento").value(nullValue())
				.jsonPath("$.fechaPrimeraConsulta").value(nullValue())
				.jsonPath("$.fechaRegistro").isNotEmpty()
				.jsonPath("$.tiempoEnEstadoSegundos").value(n -> assertThat(((Number) n).longValue()).isGreaterThanOrEqualTo(0))
				.jsonPath("$.tiempoEnEstadoMinutos").value(greaterThanOrEqualTo(0));
	}

	@Test
	void getTrasEntrarEnConsultaMuestraLaFechaDeLaPrimeraConsulta() {
		UUID id = crear(cuerpoValido());
		patch(id, "EN_CONSULTA").expectStatus().isOk();

		api().get().uri("/triage/pacientes/{id}", id).exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.estado").isEqualTo("EN_CONSULTA")
				.jsonPath("$.fechaPrimeraConsulta").isNotEmpty();
	}

	@Test
	void pacienteInexistenteEs404EnGetYPatch() {
		UUID id = UUID.randomUUID();

		api().get().uri("/triage/pacientes/{id}", id).exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody().jsonPath("$.status").isEqualTo(404).jsonPath("$.detail").value(containsString(id.toString()));
		patch(id, "ALTA").expectStatus().isNotFound();
	}

	@Test
	void idQueNoEsUuidEsUn400() {
		api().get().uri("/triage/pacientes/no-es-un-uuid").exchange()
				.expectStatus().isBadRequest()
				.expectBody().jsonPath("$.errores[*].campo").value(hasItem("id"));
	}

	@Test
	void rutaInexistenteTambienSaleComoProblemDetail() {
		api().get().uri("/triage/no-existe").exchange()
				.expectStatus().isNotFound()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
	}

	// ------------------------------------------------------------------ RN-04: auditoria + outbox

	@Test
	void registrarDejaExactamenteUnaFilaDeAuditoriaYUnaDeOutbox() {
		UUID id = crear(cuerpoValido());

		assertThat(tiposAuditoria(id)).containsExactly("PACIENTE_REGISTRADO");
		List<JsonNode> eventos = eventosOutbox(id);
		assertThat(eventos).hasSize(1);
		JsonNode evento = eventos.get(0);
		assertThat(evento.get("eventType").asText()).isEqualTo("paciente_registrado");
		// el eventId del envelope es el id de la fila del outbox
		assertThat(evento.get("eventId").asText()).isEqualTo(evento.get("_filaId").asText());
		assertThat(evento.get("version").asInt()).isEqualTo(1);
		assertThat(Instant.parse(evento.get("occurredAt").asText())).isNotNull();
		JsonNode payload = evento.get("payload");
		assertThat(payload.get("pacienteId").asText()).isEqualTo(id.toString());
		assertThat(payload.get("nivel").asText()).isEqualTo("NO_URGENTE");
		assertThat(payload.get("nivelNumero").asInt()).isEqualTo(4);
		assertThat(payload.get("estadoAnterior").isNull()).isTrue();
		assertThat(payload.get("estadoNuevo").asText()).isEqualTo("REGISTRADO");
		assertThat(payload.get("slaMinutos").asInt()).isEqualTo(60);
		assertThat(payload.get("fechaRegistro").asText()).isNotBlank();
	}

	@Test
	void cadaCambioDeEstadoAgregaUnaFilaDeAuditoriaYUnaDeOutboxConSuTipo() {
		UUID id = crear(cuerpoValido());
		patch(id, "EN_CONSULTA").expectStatus().isOk();
		patch(id, "ALTA").expectStatus().isOk();

		assertThat(tiposAuditoria(id)).containsExactlyInAnyOrder("PACIENTE_REGISTRADO", "PACIENTE_EN_CONSULTA", "PACIENTE_ALTA");
		assertThat(tiposOutbox(id)).containsExactlyInAnyOrder("paciente_registrado", "paciente_en_consulta", "paciente_alta");
		JsonNode alta = eventosOutbox(id).stream().filter(e -> e.get("eventType").asText().equals("paciente_alta")).findFirst().orElseThrow();
		assertThat(alta.get("payload").get("estadoAnterior").asText()).isEqualTo("EN_CONSULTA");
		assertThat(alta.get("payload").get("estadoNuevo").asText()).isEqualTo("ALTA");
	}

	@Test
	void laAuditoriaNoSePuedeModificarNiBorrar() {
		UUID id = crear(cuerpoValido());

		StepVerifier.create(db.sql("UPDATE auditoria_clinica SET detalle = 'x' WHERE paciente_id = :id").bind("id", id)
				.fetch().rowsUpdated())
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("inmutable")).verify();
		StepVerifier.create(db.sql("DELETE FROM auditoria_clinica WHERE paciente_id = :id").bind("id", id)
				.fetch().rowsUpdated())
				.expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("inmutable")).verify();
		assertThat(tiposAuditoria(id)).hasSize(1);
	}

	// ------------------------------------------------------------------ concurrencia (optimistic locking)

	@Test
	void dosPatchSimultaneosAlMismoPacienteUnoGanaYElOtroRecibe409() throws Exception {
		UUID id = crear(cuerpoValido());
		CountDownLatch salida = new CountDownLatch(1);

		CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> patchCuandoSalga(salida, id));
		CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> patchCuandoSalga(salida, id));
		salida.countDown();

		assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
		// exactamente un cambio quedo registrado
		assertThat(tiposAuditoria(id)).containsExactlyInAnyOrder("PACIENTE_REGISTRADO", "PACIENTE_EN_CONSULTA");
		assertThat(tiposOutbox(id)).hasSize(2);
	}

	private int patchCuandoSalga(CountDownLatch salida, UUID id) {
		try {
			salida.await();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return patch(id, "EN_CONSULTA").returnResult(String.class).getStatus().value();
	}

	/** Prueba determinista del optimistic locking en el adapter: dos lecturas con la misma version, dos escrituras. */
	@Test
	void unaEscrituraConVersionVencidaFallaConConflicto() {
		UUID id = crear(cuerpoValido());
		var lectura1 = pacientes.buscarPorId(id).block();
		var lectura2 = pacientes.buscarPorId(id).block();
		assertThat(lectura1.version()).isEqualTo(lectura2.version());
		Instant ahora = Instant.now();

		StepVerifier.create(pacientes.actualizar(
				lectura1.paciente().cambiarEstado(EstadoPaciente.EN_CONSULTA, ahora).paciente(), lectura1.version()))
				.verifyComplete();
		StepVerifier.create(pacientes.actualizar(
				lectura2.paciente().cambiarEstado(EstadoPaciente.ALTA, ahora).paciente(), lectura2.version()))
				.expectError(ConflictoConcurrenciaException.class).verify();

		var final_ = pacientes.buscarPorId(id).block();
		assertThat(final_.paciente().estado()).isEqualTo(EstadoPaciente.EN_CONSULTA);
		assertThat(final_.version()).isEqualTo(lectura1.version() + 1);
	}

	// ------------------------------------------------------------------ seguridad

	@Test
	void sinApiKeyEs401ConProblemDetail() {
		anonimo().get().uri("/triage/pacientes/{id}", UUID.randomUUID()).exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(401)
				.jsonPath("$.type").isEqualTo("urn:vidacruz:triage:problema:no-autenticado")
				.jsonPath("$.correlationId").isNotEmpty();
	}

	@Test
	void conApiKeyInvalidaEs401EnTodosLosEndpoints() {
		var cliente = anonimo().mutate().defaultHeader("X-API-Key", "clave-equivocada").build();
		UUID id = UUID.randomUUID();

		cliente.post().uri("/triage/pacientes").bodyValue(cuerpoValido()).exchange().expectStatus().isUnauthorized()
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		cliente.patch().uri("/triage/pacientes/{id}/estado", id).bodyValue(Map.of("estado", "ALTA")).exchange()
				.expectStatus().isUnauthorized();
		cliente.get().uri("/triage/pacientes/{id}", id).exchange().expectStatus().isUnauthorized();
	}

	@Test
	void unPostSinApiKeyNoCreaNada() {
		long antes = contar("pacientes");

		anonimo().post().uri("/triage/pacientes").bodyValue(cuerpoValido()).exchange().expectStatus().isUnauthorized();

		assertThat(contar("pacientes")).isEqualTo(antes);
	}

	@Test
	void laSaludSigueSiendoPublica() {
		anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
	}

	// ------------------------------------------------------------------ correlacion

	@Test
	void respetaElCorrelationIdRecibidoLoDevuelveYLoGuardaEnElEvento() {
		var resultado = api().post().uri("/triage/pacientes").header("X-Correlation-Id", "corr-abc-123")
				.bodyValue(cuerpoValido()).exchange()
				.expectStatus().isCreated()
				.expectHeader().valueEquals("X-Correlation-Id", "corr-abc-123")
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult();
		UUID id = UUID.fromString((String) resultado.getResponseBody().get("id"));

		assertThat(eventosOutbox(id).get(0).get("correlationId").asText()).isEqualTo("corr-abc-123");
	}

	@Test
	void sinCorrelationIdLoGeneraYLoUsaEnEventoYErrores() {
		var resultado = api().post().uri("/triage/pacientes").bodyValue(cuerpoValido()).exchange()
				.expectStatus().isCreated()
				.expectHeader().value("X-Correlation-Id", id -> assertThat(UUID.fromString(id)).isNotNull())
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult();
		UUID id = UUID.fromString((String) resultado.getResponseBody().get("id"));
		String generado = resultado.getResponseHeaders().getFirst("X-Correlation-Id");

		assertThat(eventosOutbox(id).get(0).get("correlationId").asText()).isEqualTo(generado);

		api().post().uri("/triage/pacientes").bodyValue(cuerpoCon("edad", 999)).exchange()
				.expectStatus().isBadRequest()
				.expectHeader().exists("X-Correlation-Id")
				.expectBody().jsonPath("$.correlationId").value(not(startsWith("sin-")));
	}

	@Test
	void unCorrelationIdConCaracteresPeligrososSeReemplaza() {
		api().get().uri("/triage/pacientes/{id}", UUID.randomUUID()).header("X-Correlation-Id", "malo valor con espacios")
				.exchange()
				.expectHeader().value("X-Correlation-Id", v -> assertThat(v).doesNotContain(" ").hasSize(36));
	}

	/** Verifica que el Context de Reactor llega al MDC: el log del caso de uso lleva el correlationId del request. */
	@Test
	void elLogDelCasoDeUsoIncluyeElCorrelationIdEnElMdcYNoElNombre() {
		Logger logger = (Logger) LoggerFactory.getLogger("com.vidacruz.triage.application.usecase.RegistrarPacienteUseCaseImpl");
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			api().post().uri("/triage/pacientes").header("X-Correlation-Id", "corr-log-777")
					.bodyValue(cuerpoCon("nombreCompleto", "Nombre Secreto Apellido")).exchange().expectStatus().isCreated();

			ILoggingEvent evento = appender.list.stream().filter(e -> e.getFormattedMessage().contains("Paciente registrado"))
					.findFirst().orElseThrow();
			assertThat(evento.getMDCPropertyMap()).containsEntry("correlationId", "corr-log-777");
			assertThat(evento.getFormattedMessage()).doesNotContain("Secreto").contains("nivel=NO_URGENTE");
		}
		finally {
			logger.detachAppender(appender);
		}
	}

}
