package com.vidacruz.assistant.infrastructure.entrypoint.scheduler;

import com.vidacruz.assistant.application.port.in.IndexarProtocolosUseCase;
import com.vidacruz.assistant.infrastructure.config.AsistenteRagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Indexa los protocolos al arrancar, EN SEGUNDO PLANO: la aplicacion arranca aunque el proveedor de embeddings o Redis
 * fallen. Si la indexacion falla, reintenta con backoff exponencial (con jitter) hasta lograrlo; mientras tanto la consulta
 * responde 503 y la readiness esta en DOWN (indicador {@code indiceProtocolos}), pero la liveness sigue en UP.
 */
@Component
class IndexacionProtocolosArranque implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(IndexacionProtocolosArranque.class);

	private final IndexarProtocolosUseCase indexar;
	private final AsistenteRagProperties rag;
	private volatile Disposable tarea;
	private volatile boolean activo;

	IndexacionProtocolosArranque(IndexarProtocolosUseCase indexar, AsistenteRagProperties rag) {
		this.indexar = indexar;
		this.rag = rag;
	}

	@Override
	public void start() {
		activo = true;
		if (!rag.ingestOnStartup()) {
			indexar.asumirIndiceExistente();
			return;
		}
		tarea = Mono.defer(indexar::indexar)
				.retryWhen(Retry.backoff(Long.MAX_VALUE, rag.ingesta().backoffInicial())
						.maxBackoff(rag.ingesta().backoffMaximo())
						.jitter(0.2)
						.doBeforeRetry(senal -> log.warn("La indexacion de protocolos fallo (intento {}): {}. Se reintenta con backoff.",
								senal.totalRetries() + 1, senal.failure().toString())))
				.subscribe(resultado -> { }, error -> log.error("La indexacion de protocolos termino con error", error));
	}

	@Override
	public void stop() {
		activo = false;
		Disposable actual = tarea;
		if (actual != null) {
			actual.dispose();
		}
	}

	@Override
	public boolean isRunning() {
		return activo;
	}

}
