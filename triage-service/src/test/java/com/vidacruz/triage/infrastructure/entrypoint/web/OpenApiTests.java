package com.vidacruz.triage.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.support.AbstractPostgresTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

/** La documentacion OpenAPI se publica sin API key y describe los 5 endpoints con sus errores y la seguridad. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiTests extends AbstractPostgresTest {

	@Autowired
	ObjectMapper objectMapper;

	JsonNode docs;

	@BeforeAll
	void descargar() throws Exception {
		byte[] cuerpo = anonimo().get().uri("/v3/api-docs").exchange()
				.expectStatus().isOk().expectBody().returnResult().getResponseBody();
		docs = objectMapper.readTree(cuerpo);
	}

	private JsonNode operacion(String ruta, String metodo) {
		JsonNode op = docs.path("paths").path(ruta).path(metodo);
		assertThat(op.isMissingNode()).as("%s %s documentado", metodo.toUpperCase(), ruta).isFalse();
		return op;
	}

	private static List<String> codigos(JsonNode op) {
		List<String> codigos = new ArrayList<>();
		op.get("responses").fieldNames().forEachRemaining(codigos::add);
		return codigos;
	}

	@Test
	void documentaLosCincoEndpointsSinNecesitarApiKey() {
		assertThat(docs.get("openapi").asText()).startsWith("3.");
		assertThat(operacion("/triage/pacientes", "post").get("summary").asText()).isNotBlank();
		assertThat(operacion("/triage/pacientes/{id}/estado", "patch").get("summary").asText()).isNotBlank();
		assertThat(operacion("/triage/pacientes/{id}", "get").get("summary").asText()).isNotBlank();
		assertThat(operacion("/triage/alertas/stream", "get").get("summary").asText()).isNotBlank();
		assertThat(operacion("/triage/reportes/operacion", "get").get("summary").asText()).isNotBlank();
	}

	@Test
	void declaraElEsquemaDeSeguridadApiKeyEnElHeaderAplicadoGlobalmente() {
		JsonNode esquema = docs.at("/components/securitySchemes/ApiKeyAuth");
		assertThat(esquema.get("type").asText()).isEqualTo("apiKey");
		assertThat(esquema.get("in").asText()).isEqualTo("header");
		assertThat(esquema.get("name").asText()).isEqualTo("X-API-Key");
		assertThat(docs.get("security").toString()).contains("ApiKeyAuth");
	}

	@Test
	void cadaEndpointListaSusCodigosDeRespuesta() {
		assertThat(codigos(operacion("/triage/pacientes", "post"))).contains("201", "400", "401", "500");
		assertThat(codigos(operacion("/triage/pacientes/{id}/estado", "patch"))).contains("200", "400", "401", "404", "409", "500");
		assertThat(codigos(operacion("/triage/pacientes/{id}", "get"))).contains("200", "400", "401", "404", "500");
		assertThat(codigos(operacion("/triage/alertas/stream", "get"))).contains("200", "401", "500");
		assertThat(codigos(operacion("/triage/reportes/operacion", "get"))).contains("200", "400", "401", "500");
	}

	@Test
	void elRegistroTieneLosEjemplosE1YE5() {
		JsonNode ejemplos = operacion("/triage/pacientes", "post").at("/requestBody/content/application~1json/examples");
		assertThat(ejemplos.toString()).contains("E1").contains("E5").contains("Dolor torácico")
				.contains("Renovación de recetario");
	}

	@Test
	void elStreamDeAlertasDocumentaTextEventStream() {
		JsonNode contenido = operacion("/triage/alertas/stream", "get").at("/responses/200/content");
		assertThat(contenido.has("text/event-stream")).isTrue();
	}

	@Test
	void elErrorUsaProblemDetailYSeDocumentaConProblemJson() {
		JsonNode c409 = operacion("/triage/pacientes/{id}/estado", "patch").at("/responses/409/content");
		assertThat(c409.has("application/problem+json")).isTrue();
		assertThat(c409.toString()).contains("ProblemDetail");
	}

	@Test
	void losParametrosDelReporteTienenDescripcionYEjemplo() {
		JsonNode parametros = operacion("/triage/reportes/operacion", "get").get("parameters");
		assertThat(parametros).hasSize(2);
		for (JsonNode p : parametros) {
			assertThat(p.get("required").asBoolean()).isTrue();
			assertThat(p.get("description").asText()).isNotBlank();
			assertThat(p.get("example").asText()).contains("-05:00");
		}
	}

	@Test
	void losDtosDeRegistroTienenLosRangosDelEnunciado() {
		JsonNode props = docs.at("/components/schemas/RegistrarPacienteRequest/properties");
		assertThat(props.at("/frecuenciaCardiaca/minimum").asInt()).isEqualTo(20);
		assertThat(props.at("/frecuenciaCardiaca/maximum").asInt()).isEqualTo(250);
		assertThat(props.at("/presionSistolica/minimum").asInt()).isEqualTo(60);
		assertThat(props.at("/presionSistolica/maximum").asInt()).isEqualTo(260);
		assertThat(props.at("/spo2/minimum").asInt()).isEqualTo(70);
		assertThat(props.at("/gcs/minimum").asInt()).isEqualTo(3);
		assertThat(props.at("/dolor/maximum").asInt()).isEqualTo(10);
		assertThat(props.at("/nombreCompleto/minLength").asInt()).isEqualTo(3);
		assertThat(props.at("/nombreCompleto/maxLength").asInt()).isEqualTo(100);
		JsonNode estados = docs.at("/components/schemas/CambiarEstadoRequest/properties/estado/enum");
		assertThat(estados.toString()).contains("REGISTRADO", "EN_CONSULTA", "ALTA");
	}

	@Test
	void swaggerUiEsAccesibleSinApiKey() {
		anonimo().get().uri("/swagger-ui.html").exchange().expectStatus().is3xxRedirection();
		anonimo().get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
	}

	@Test
	void losEndpointsDeNegocioSiguenExigiendoApiKey() {
		anonimo().get().uri("/triage/pacientes/" + java.util.UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
	}

}
