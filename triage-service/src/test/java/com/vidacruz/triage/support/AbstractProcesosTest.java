package com.vidacruz.triage.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Ayudas comunes de los tests de procesos de fondo: registrar pacientes por la API y leer outbox/auditoria.
 * No fija el broker de Kafka: cada subclase registra el suyo.
 */
public abstract class AbstractProcesosTest extends AbstractPostgresTest {

	@Autowired
	protected ObjectMapper objectMapper;

	/** FC 130 -> nivel CRITICO (SLA 5 min por defecto). */
	protected static Map<String, Object> cuerpoCritico() {
		Map<String, Object> m = cuerpoNormal();
		m.put("frecuenciaCardiaca", 130);
		return m;
	}

	/** Signos normales y queja no administrativa -> NO_URGENTE (SLA 60 min por defecto). */
	protected static Map<String, Object> cuerpoNormal() {
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

	protected UUID registrar(Map<String, Object> cuerpo) {
		return registrar(cuerpo, null);
	}

	protected UUID registrar(Map<String, Object> cuerpo, String correlationId) {
		var peticion = api().post().uri("/triage/pacientes");
		if (correlationId != null) {
			peticion = peticion.header("X-Correlation-Id", correlationId);
		}
		Map<String, Object> r = peticion.bodyValue(cuerpo).exchange().expectStatus().isCreated()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult().getResponseBody();
		return UUID.fromString((String) r.get("id"));
	}

	protected WebTestClient.ResponseSpec cambiarEstado(UUID id, String estado) {
		return api().patch().uri("/triage/pacientes/{id}/estado", id).bodyValue(Map.of("estado", estado)).exchange();
	}

	/** Envelopes del outbox de un paciente, con el campo extra {@code _publicado} (true si published_at no es null). */
	protected List<JsonNode> outbox(UUID pacienteId) {
		return db.sql("SELECT payload::text AS payload, published_at IS NOT NULL AS publicado FROM outbox_eventos "
						+ "WHERE aggregate_id = :id ORDER BY created_at, id")
				.bind("id", pacienteId)
				.map(fila -> {
					try {
						var nodo = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(fila.get("payload", String.class));
						nodo.put("_publicado", fila.get("publicado", Boolean.class));
						return (JsonNode) nodo;
					}
					catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).all().collectList().block();
	}

	protected long contarEventos(UUID pacienteId, String tipo) {
		return outbox(pacienteId).stream().filter(e -> e.get("eventType").asText().equals(tipo)).count();
	}

	protected long contarAuditoria(UUID pacienteId, String tipo) {
		return tiposAuditoria(pacienteId).stream().filter(tipo::equals).count();
	}

	protected JsonNode paciente(UUID id) {
		return api().get().uri("/triage/pacientes/{id}", id).exchange().expectStatus().isOk()
				.expectBody(JsonNode.class).returnResult().getResponseBody();
	}

}
