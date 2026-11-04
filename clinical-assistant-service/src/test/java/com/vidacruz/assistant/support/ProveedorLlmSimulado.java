package com.vidacruz.assistant.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Proveedor de IA simulado: un WireMock que imita la API de OpenAI (/v1/chat/completions y /v1/embeddings), compartido
 * por toda la JVM de tests.
 * <p>
 * <b>Embeddings deterministas.</b> Un transformer calcula el vector de cada texto por <i>palabras clave</i>: una dimension
 * por tema (hipertension, sepsis, deshidratacion, diabetes) mas una componente constante para que nunca sea el vector
 * cero. Textos del mismo tema quedan casi paralelos y de temas distintos casi ortogonales, asi la recuperacion es
 * reproducible (sin depender de un modelo real) PERO se sigue ejerciendo el camino real: el cliente HTTP de Spring AI,
 * el RedisVectorStore con busqueda KNN de verdad y los errores/timeouts del proveedor. Un EmbeddingModel falso en memoria
 * se saltaria justamente la parte que Resilience4j protege.
 * <p>
 * <b>Chat por reglas.</b> Responde segun la pregunta (las dos consultas de ejemplo con contenido del corpus; cualquier otra
 * cosa, {@code respondida=false}). Cada test puede sustituir los stubs y {@link #reiniciar()} deja los de por defecto.
 */
public final class ProveedorLlmSimulado {

	public static final int DIMENSIONES = 1536;   // text-embedding-3-small
	public static final String RUTA_CHAT = "/v1/chat/completions";
	public static final String RUTA_EMBEDDINGS = "/v1/embeddings";

	private static final ObjectMapper JSON = new ObjectMapper();

	/** Temas: indice de dimension (1..4) -> palabras clave (sin tildes, minusculas). La dimension 0 es la constante. */
	private static final List<List<String>> TEMAS = List.of(
			List.of("hipertensiv", "presion arterial", "180/120", "nifedipino", "encefalopatia", "organo diana", "reposo"),
			List.of("sepsis", "qsofa", "antibiotico", "lactato", "hemocultivo", "cristaloide", "hipotension", "frecuencia respiratoria"),
			List.of("deshidrat", "pediatric", "rehidrat", "solucion oral", "bolo"),
			List.of("diabetes", "insulina", "glucosa", "metformina", "glicemia"));

	private static final WireMockServer SERVIDOR = new WireMockServer(
			WireMockConfiguration.options().dynamicPort().extensions(new EmbeddingsPorPalabrasClave()));

	static {
		SERVIDOR.start();
		reiniciar();
	}

	private ProveedorLlmSimulado() {
	}

	public static WireMockServer servidor() {
		return SERVIDOR;
	}

	public static String url() {
		return "http://localhost:" + SERVIDOR.port();
	}

	/** Borra stubs y peticiones registradas y deja los stubs por defecto. */
	public static void reiniciar() {
		SERVIDOR.resetAll();
		stubEmbeddingsOk();
		SERVIDOR.stubFor(post(urlEqualTo(RUTA_CHAT)).atPriority(1)
				.withRequestBody(matchingJsonPath("$.messages[1].content", containing("crisis hipertensiva")))
				.willReturn(chat(respondida("Reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la primera hora. Vigilar signos de encefalopatía.",
						"PROTO-001", "Medidas iniciales"))));
		SERVIDOR.stubFor(post(urlEqualTo(RUTA_CHAT)).atPriority(1)
				.withRequestBody(matchingJsonPath("$.messages[1].content", containing("primer antibi")))
				.willReturn(chat(respondida("El antibiótico de amplio espectro se administra en la primera hora, después de tomar lactato y hemocultivos.",
						"PROTO-002", "Acciones iniciales"))));
		SERVIDOR.stubFor(post(urlEqualTo(RUTA_CHAT)).atPriority(10)
				.willReturn(chat("{\"respondida\":false,\"respuesta\":\"\",\"citas\":[]}")));
	}

	public static void stubEmbeddingsOk() {
		SERVIDOR.stubFor(post(urlEqualTo(RUTA_EMBEDDINGS)).atPriority(5).willReturn(
				aResponse().withHeader("Content-Type", "application/json").withTransformers(EmbeddingsPorPalabrasClave.NOMBRE)));
	}

	public static int llamadasChat() {
		return SERVIDOR.findAll(postRequestedFor(urlEqualTo(RUTA_CHAT))).size();
	}

	public static int llamadasEmbeddings() {
		return SERVIDOR.findAll(postRequestedFor(urlEqualTo(RUTA_EMBEDDINGS))).size();
	}

	/** Textos (campo {@code input}) que se enviaron a /v1/embeddings, en orden. */
	public static List<String> textosEmbebidos() {
		return SERVIDOR.findAll(postRequestedFor(urlEqualTo(RUTA_EMBEDDINGS))).stream().flatMap(r -> {
			try {
				JsonNode entrada = JSON.readTree(r.getBodyAsString()).get("input");
				return entrada.isArray() ? java.util.stream.StreamSupport.stream(entrada.spliterator(), false).map(JsonNode::asText)
						: java.util.stream.Stream.of(entrada.asText());
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}).toList();
	}

	// ------------------------------------------------------------------ construccion de respuestas

	/** JSON {@code respondida=true} con una cita. */
	public static String respondida(String texto, String protocolo, String seccion) {
		ObjectNode o = JSON.createObjectNode().put("respondida", true).put("respuesta", texto);
		o.putArray("citas").addObject().put("protocolo", protocolo).put("seccion", seccion);
		return o.toString();
	}

	/** Cuerpo de una respuesta de chat de OpenAI cuyo contenido (texto del asistente) es {@code contenido}. */
	public static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder chat(String contenido) {
		ObjectNode raiz = JSON.createObjectNode().put("id", "chatcmpl-test").put("object", "chat.completion")
				.put("created", 1730000000L).put("model", "gpt-4o-mini");
		ObjectNode mensaje = JSON.createObjectNode().put("role", "assistant").put("content", contenido);
		raiz.putArray("choices").addObject().put("index", 0).set("message", mensaje);
		((ObjectNode) raiz.get("choices").get(0)).put("finish_reason", "stop");
		raiz.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 40).put("total_tokens", 160);
		return aResponse().withHeader("Content-Type", "application/json").withBody(raiz.toString());
	}

	// ------------------------------------------------------------------ embeddings por palabras clave

	/** Vector de un texto: componente constante + una dimension por tema (conteo de palabras clave, con tope). */
	static float[] vectorDe(String texto) {
		String t = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
		float[] v = new float[DIMENSIONES];
		v[0] = 0.3f;
		for (int tema = 0; tema < TEMAS.size(); tema++) {
			int aciertos = 0;
			for (String clave : TEMAS.get(tema)) {
				int desde = 0;
				while ((desde = t.indexOf(clave, desde)) >= 0) {
					aciertos++;
					desde += clave.length();
				}
			}
			v[tema + 1] = Math.min(aciertos, 4);
		}
		return v;
	}

	public static final class EmbeddingsPorPalabrasClave implements ResponseTransformerV2 {

		static final String NOMBRE = "embeddings-por-palabras-clave";

		@Override
		public String getName() {
			return NOMBRE;
		}

		@Override
		public boolean applyGlobally() {
			return false;
		}

		@Override
		public Response transform(Response respuesta, ServeEvent evento) {
			try {
				JsonNode entrada = JSON.readTree(evento.getRequest().getBodyAsString()).get("input");
				ObjectNode raiz = JSON.createObjectNode().put("object", "list").put("model", "text-embedding-3-small");
				ArrayNode datos = raiz.putArray("data");
				int i = 0;
				for (JsonNode texto : entrada.isArray() ? entrada : JSON.createArrayNode().add(entrada)) {
					ObjectNode d = datos.addObject().put("object", "embedding").put("index", i++);
					ArrayNode vector = d.putArray("embedding");
					for (float x : vectorDe(texto.asText())) {
						vector.add(x);
					}
				}
				raiz.putObject("usage").put("prompt_tokens", 10).put("total_tokens", 10);
				return Response.Builder.like(respuesta).but().body(raiz.toString()).build();
			}
			catch (IOException e) {
				throw new IllegalStateException("Peticion de embeddings ilegible", e);
			}
		}

	}

}
