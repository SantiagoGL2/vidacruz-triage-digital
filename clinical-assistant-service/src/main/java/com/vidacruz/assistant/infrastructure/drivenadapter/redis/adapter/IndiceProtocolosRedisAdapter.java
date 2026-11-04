package com.vidacruz.assistant.infrastructure.drivenadapter.redis.adapter;

import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.infrastructure.config.AsistenteRagProperties;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience.ProtectorLlm;
import com.vidacruz.assistant.infrastructure.drivenadapter.redis.indice.ConstructorIndice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path2;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Indice de protocolos en Redis Stack (RediSearch + RedisJSON) con el RedisVectorStore de Spring AI.
 * <p>
 * {@link #sincronizar} es idempotente: lee los ids y hashes que ya hay bajo el prefijo del indice, y solo calcula
 * embeddings (llamada al proveedor, protegida con Resilience4j) de los fragmentos nuevos o cuyo hash cambio; borra los que
 * ya no estan en el corpus. Reiniciar la aplicacion no duplica ni llama al proveedor. Todo es bloqueante (Jedis, Spring AI),
 * asi que se ejecuta en boundedElastic.
 */
@Component
class IndiceProtocolosRedisAdapter implements IndiceProtocolosPort {

	private static final Logger log = LoggerFactory.getLogger(IndiceProtocolosRedisAdapter.class);
	private static final Pattern HASH = Pattern.compile("\"([^\"]+)\"");

	private final JedisPooled jedis;
	private final EmbeddingModel embeddings;
	private final VectorStore vectorStore;
	private final ProtectorLlm protector;
	private final String nombreIndice;
	private final String prefijo;
	private final String modelo;

	IndiceProtocolosRedisAdapter(JedisPooled jedis, EmbeddingModel embeddings, VectorStore vectorStore,
			ProtectorLlm protector, AsistenteRagProperties rag) {
		this.jedis = jedis;
		this.embeddings = embeddings;
		this.vectorStore = vectorStore;
		this.protector = protector;
		this.nombreIndice = rag.indice().nombre();
		this.prefijo = rag.indice().prefijo();
		this.modelo = rag.modeloEmbeddings();
	}

	@Override
	public Mono<ResultadoSincronizacion> sincronizar(List<FragmentoProtocolo> deseados) {
		return Mono.fromCallable(() -> planear(deseados)).subscribeOn(Schedulers.boundedElastic())
				.flatMap(plan -> aplicar(plan, deseados.size()));
	}

	/** Compara lo deseado con lo indexado. Solo lee de Redis; crea el indice si aun no existe. */
	private Plan planear(List<FragmentoProtocolo> deseados) {
		crearIndiceSiNoExiste();
		Map<String, String> existentes = leerExistentes();
		List<FragmentoProtocolo> nuevos = new ArrayList<>();
		List<FragmentoProtocolo> cambiados = new ArrayList<>();
		int sinCambios = 0;
		Set<String> idsDeseados = new HashSet<>();
		for (FragmentoProtocolo f : deseados) {
			idsDeseados.add(f.id());
			String hashActual = existentes.get(f.id());
			if (hashActual == null) {
				nuevos.add(f);
			}
			else if (!hashActual.equals(hashIndexado(f))) {
				cambiados.add(f);
			}
			else {
				sinCambios++;
			}
		}
		List<String> obsoletos = existentes.keySet().stream().filter(id -> !idsDeseados.contains(id)).sorted().toList();
		return new Plan(nuevos, cambiados, obsoletos, sinCambios);
	}

	private Mono<ResultadoSincronizacion> aplicar(Plan plan, int total) {
		Mono<Void> borrar = plan.obsoletos().isEmpty() ? Mono.empty()
				: Mono.<Void>fromRunnable(() -> vectorStore.delete(plan.obsoletos())).subscribeOn(Schedulers.boundedElastic());
		List<Document> aIndexar = new ArrayList<>();
		plan.nuevos().forEach(f -> aIndexar.add(documento(f)));
		plan.cambiados().forEach(f -> aIndexar.add(documento(f)));
		// calcular embeddings = llamar al proveedor: con circuit breaker, reintentos y timeout (instancia llm-embedding)
		Mono<Void> indexar = aIndexar.isEmpty() ? Mono.empty()
				: protector.ejecutar("embedding", () -> {
					vectorStore.add(aIndexar);
					return Boolean.TRUE;
				}).then();
		return borrar.then(indexar).thenReturn(new ResultadoSincronizacion(plan.nuevos().size(), plan.cambiados().size(),
				plan.obsoletos().size(), plan.sinCambios())).doOnNext(r -> log.debug("Sincronizacion: {} deseados, {}", total, r));
	}

	private void crearIndiceSiNoExiste() {
		// Mismo codigo que usaria Spring AI en el arranque (FT.INFO / FT.CREATE si falta), pero invocado aqui, con reintentos.
		ConstructorIndice.crear(jedis, embeddings, nombreIndice, prefijo, true).afterPropertiesSet();
	}

	/** id del fragmento -> hash guardado. Recorre con SCAN las claves del prefijo del indice (no bloquea Redis con KEYS). */
	private Map<String, String> leerExistentes() {
		Map<String, String> existentes = new HashMap<>();
		String cursor = ScanParams.SCAN_POINTER_START;
		ScanParams parametros = new ScanParams().match(prefijo + "*").count(100);
		do {
			ScanResult<String> resultado = jedis.scan(cursor, parametros);
			for (String clave : resultado.getResult()) {
				Object hash = jedis.jsonGet(clave, Path2.of("$.hash"));
				Matcher m = HASH.matcher(String.valueOf(hash));
				existentes.put(clave.substring(prefijo.length()), m.find() ? m.group(1) : "");
			}
			cursor = resultado.getCursor();
		}
		while (!ScanParams.SCAN_POINTER_START.equals(cursor));
		return existentes;
	}

	/**
	 * Lo que se guarda como hash en el indice: contenido + version del fragmento + modelo de embeddings. Si cambia el
	 * modelo (los vectores guardados ya no son comparables con los de las preguntas), todo se reindexa; el hash del
	 * dominio solo no lo detectaria.
	 */
	private String hashIndexado(FragmentoProtocolo f) {
		return f.hash() + "@" + modelo;
	}

	private Document documento(FragmentoProtocolo f) {
		Map<String, Object> metadatos = new HashMap<>();
		metadatos.put(ConstructorIndice.CAMPO_PROTOCOLO, f.protocolo());
		metadatos.put(ConstructorIndice.CAMPO_TITULO, f.titulo());
		metadatos.put(ConstructorIndice.CAMPO_SECCION, f.seccion());
		metadatos.put(ConstructorIndice.CAMPO_VERSION, f.version());
		metadatos.put(ConstructorIndice.CAMPO_HASH, hashIndexado(f));
		return Document.builder().id(f.id()).text(f.texto()).metadata(metadatos).build();
	}

	private record Plan(List<FragmentoProtocolo> nuevos, List<FragmentoProtocolo> cambiados, List<String> obsoletos,
			int sinCambios) {
	}

}
