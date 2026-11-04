package com.vidacruz.assistant.infrastructure.drivenadapter.classpath.adapter;

import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.service.LectorProtocolos;
import com.vidacruz.assistant.infrastructure.config.AsistenteRagProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Lee el corpus: un archivo Markdown por protocolo en {@code classpath:protocolos/*.md} (configurable con
 * {@code assistant.rag.corpus}). Agregar un protocolo es soltar un archivo nuevo ahi. Leer archivos es bloqueante, asi que
 * va en boundedElastic. Un archivo mal formado hace fallar toda la carga (no se indexa un corpus a medias).
 */
@Component
class CorpusProtocolosAdapter implements FuenteProtocolosPort {

	private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
	private final LectorProtocolos lector;
	private final String patron;

	CorpusProtocolosAdapter(LectorProtocolos lector, AsistenteRagProperties rag) {
		this.lector = lector;
		this.patron = rag.corpus();
	}

	@Override
	public Mono<List<Protocolo>> cargar() {
		return Mono.fromCallable(this::leerTodos).subscribeOn(Schedulers.boundedElastic());
	}

	private List<Protocolo> leerTodos() throws IOException {
		Resource[] recursos = resolver.getResources(patron);
		return Arrays.stream(recursos)
				.sorted(Comparator.comparing(r -> String.valueOf(r.getFilename())))
				.map(r -> {
					try (var in = r.getInputStream()) {
						return lector.leer(new String(in.readAllBytes(), StandardCharsets.UTF_8), String.valueOf(r.getFilename()));
					}
					catch (IOException e) {
						throw new UncheckedIOException("No se pudo leer " + r, e);
					}
				})
				.toList();
	}

}
