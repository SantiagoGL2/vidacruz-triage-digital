package com.vidacruz.assistant.infrastructure.drivenadapter.redis.adapter;

import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.ParametrosRecuperacion;
import com.vidacruz.assistant.domain.port.out.BuscadorProtocolosPort;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience.ProtectorLlm;
import com.vidacruz.assistant.infrastructure.drivenadapter.redis.indice.ConstructorIndice;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Barrera 1: busqueda KNN top-K con umbral de similitud en Redis Stack. La busqueda calcula el embedding de la pregunta
 * (llamada al proveedor) y consulta el indice; es bloqueante, asi que va en boundedElastic con la proteccion de la
 * instancia {@code llm-embedding}. El umbral lo aplica la propia consulta vectorial: lo que devuelve ya lo supera.
 */
@Component
class BuscadorProtocolosRedisAdapter implements BuscadorProtocolosPort {

	private final VectorStore vectorStore;
	private final ProtectorLlm protector;

	BuscadorProtocolosRedisAdapter(VectorStore vectorStore, ProtectorLlm protector) {
		this.vectorStore = vectorStore;
		this.protector = protector;
	}

	@Override
	public Mono<List<FragmentoRecuperado>> buscar(String pregunta, ParametrosRecuperacion parametros) {
		SearchRequest peticion = SearchRequest.builder()
				.query(pregunta)
				.topK(parametros.topK())
				.similarityThreshold(parametros.umbralSimilitud())
				.build();
		return protector.ejecutar("embedding", () -> vectorStore.similaritySearch(peticion))
				.map(documentos -> documentos.stream()
						.map(BuscadorProtocolosRedisAdapter::aFragmento)
						.sorted(Comparator.comparingDouble(FragmentoRecuperado::puntuacion).reversed())
						.toList());
	}

	private static FragmentoRecuperado aFragmento(Document d) {
		Map<String, Object> m = d.getMetadata();
		var fragmento = new FragmentoProtocolo(d.getId(), texto(m, ConstructorIndice.CAMPO_PROTOCOLO),
				texto(m, ConstructorIndice.CAMPO_TITULO), texto(m, ConstructorIndice.CAMPO_SECCION),
				texto(m, ConstructorIndice.CAMPO_VERSION), d.getText(), texto(m, ConstructorIndice.CAMPO_HASH));
		return new FragmentoRecuperado(fragmento, d.getScore() == null ? 0.0 : d.getScore());
	}

	private static String texto(Map<String, Object> metadatos, String campo) {
		Object valor = metadatos.get(campo);
		return valor == null ? "" : valor.toString();
	}

}
