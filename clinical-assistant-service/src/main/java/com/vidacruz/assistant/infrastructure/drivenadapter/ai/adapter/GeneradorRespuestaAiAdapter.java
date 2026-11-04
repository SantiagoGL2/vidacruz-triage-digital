package com.vidacruz.assistant.infrastructure.drivenadapter.ai.adapter;

import com.vidacruz.assistant.domain.model.CitaProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.RespuestaGenerada;
import com.vidacruz.assistant.domain.model.SalidaGenerador;
import com.vidacruz.assistant.domain.port.out.GeneradorRespuestaPort;
import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.dto.SalidaLlm;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.prompt.PromptsRag;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience.ProtectorLlm;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Barrera 2: genera la respuesta con el modelo de chat (temperatura 0, prompt estricto, salida estructurada). La llamada
 * va protegida ({@link ProtectorLlm}, instancia {@code llm-chat}); el parseo de la salida ocurre DESPUES, fuera del circuit
 * breaker: una salida mal formada es un problema de contenido, no una falla del proveedor.
 */
@Component
class GeneradorRespuestaAiAdapter implements GeneradorRespuestaPort {

	private static final Logger log = LoggerFactory.getLogger(GeneradorRespuestaAiAdapter.class);

	private final ChatModel chatModel;
	private final ProtectorLlm protector;
	private final MetricasRagPort metricas;
	/**
	 * Lector tolerante a defectos de formato frecuentes de los modelos (coma final antes de la llave de cierre, visto con
	 * gpt-4o-mini), que no cambian el contenido. Lo que siga sin ser JSON valido es NoParseable -> «sin protocolo».
	 */
	private final BeanOutputConverter<SalidaLlm> conversor = new BeanOutputConverter<>(SalidaLlm.class,
			JsonMapper.builder().enable(JsonReadFeature.ALLOW_TRAILING_COMMA).build());

	GeneradorRespuestaAiAdapter(ChatModel chatModel, ProtectorLlm protector, MetricasRagPort metricas) {
		this.chatModel = chatModel;
		this.protector = protector;
		this.metricas = metricas;
	}

	@Override
	public Mono<SalidaGenerador> generar(String pregunta, List<FragmentoRecuperado> contexto) {
		Prompt prompt = new Prompt(
				List.of(new SystemMessage(PromptsRag.SISTEMA),
						new UserMessage(PromptsRag.usuario(pregunta, contexto, conversor.getFormat()))),
				OpenAiChatOptions.builder().temperature(0.0).build());
		return protector.ejecutar("chat", () -> chatModel.call(prompt)).map(this::interpretar);
	}

	private SalidaGenerador interpretar(ChatResponse respuesta) {
		registrarTokens(respuesta);
		String texto = respuesta.getResult() == null || respuesta.getResult().getOutput() == null ? null
				: respuesta.getResult().getOutput().getText();
		if (texto == null || texto.isBlank()) {
			return new SalidaGenerador.NoParseable("respuesta vacia del modelo");
		}
		try {
			SalidaLlm salida = conversor.convert(extraerJson(texto));
			if (salida == null) {
				return new SalidaGenerador.NoParseable("JSON nulo");
			}
			List<CitaProtocolo> citas = salida.citas() == null ? List.of()
					: salida.citas().stream().filter(c -> c != null)
							.map(c -> new CitaProtocolo(c.protocolo() == null ? "" : c.protocolo(),
									c.seccion() == null ? "" : c.seccion()))
							.toList();
			return new SalidaGenerador.Generada(new RespuestaGenerada(salida.respondida(), salida.respuesta(), citas));
		}
		catch (RuntimeException e) {
			log.warn("Salida del modelo no parseable: {}", e.toString());
			// el texto del modelo es contenido de protocolos, no datos del paciente; solo a nivel DEBUG y recortado
			log.debug("Salida no parseable (primeros 500 caracteres): {}", texto.length() > 500 ? texto.substring(0, 500) : texto);
			return new SalidaGenerador.NoParseable(e.getClass().getSimpleName());
		}
	}

	/** Recorta lo que haya antes del primer '{' y despues del ultimo '}' (cercos ```json u otro texto suelto). */
	private static String extraerJson(String texto) {
		int inicio = texto.indexOf('{');
		int fin = texto.lastIndexOf('}');
		return inicio >= 0 && fin > inicio ? texto.substring(inicio, fin + 1) : texto;
	}

	private void registrarTokens(ChatResponse respuesta) {
		var uso = respuesta.getMetadata() == null ? null : respuesta.getMetadata().getUsage();
		if (uso == null) {
			return;
		}
		if (uso.getPromptTokens() != null && uso.getPromptTokens() > 0) {
			metricas.tokens("prompt", uso.getPromptTokens());
		}
		if (uso.getCompletionTokens() != null && uso.getCompletionTokens() > 0) {
			metricas.tokens("completion", uso.getCompletionTokens());
		}
	}

}
