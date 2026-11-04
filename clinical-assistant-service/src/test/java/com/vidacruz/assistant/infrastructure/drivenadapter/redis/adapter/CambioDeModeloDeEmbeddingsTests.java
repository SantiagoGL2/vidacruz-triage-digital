package com.vidacruz.assistant.infrastructure.drivenadapter.redis.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.domain.service.FragmentadorProtocolos;
import com.vidacruz.assistant.infrastructure.config.AsistenteRagProperties;
import com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience.ProtectorLlm;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import redis.clients.jedis.JedisPooled;

/**
 * Los vectores guardados solo son comparables con los de las preguntas si salen del MISMO modelo de embeddings. Por eso el
 * hash del indice incluye el modelo: al cambiarlo (o al pasar de un proveedor simulado a uno real) se reindexa todo solo,
 * sin tener que acordarse de borrar el indice a mano.
 */
class CambioDeModeloDeEmbeddingsTests extends AbstractIntegrationTest {

	@Autowired
	JedisPooled jedis;

	@Autowired
	EmbeddingModel embeddings;

	@Autowired
	VectorStore vectorStore;

	@Autowired
	ProtectorLlm protector;

	@Autowired
	AsistenteRagProperties rag;

	@Autowired
	IndiceProtocolosPort indiceActual;

	@Autowired
	FuenteProtocolosPort fuente;

	@Autowired
	FragmentadorProtocolos fragmentador;

	@Test
	void cambiarElModeloDeEmbeddingsReindexaTodosLosFragmentos() {
		var fragmentos = fragmentador.fragmentar(fuente.cargar().block(Duration.ofSeconds(10)));
		var conOtroModelo = new AsistenteRagProperties(rag.topK(), rag.similarityThreshold(), rag.mensajeSinProtocolo(),
				rag.corpus(), rag.ingestOnStartup(), "otro-modelo-de-embeddings", rag.indice(), rag.ingesta(), rag.retryAfter());
		var adaptadorConOtroModelo = new IndiceProtocolosRedisAdapter(jedis, embeddings, vectorStore, protector, conOtroModelo);
		try {
			ResultadoSincronizacion r = adaptadorConOtroModelo.sincronizar(fragmentos).block(Duration.ofSeconds(60));

			assertThat(r).isEqualTo(new ResultadoSincronizacion(0, 7, 0, 0));
			// con el mismo modelo, nada que hacer
			assertThat(adaptadorConOtroModelo.sincronizar(fragmentos).block(Duration.ofSeconds(60)))
					.isEqualTo(new ResultadoSincronizacion(0, 0, 0, 7));
		}
		finally {
			// el modelo configurado vuelve a ser el de la aplicacion: se reindexa otra vez
			assertThat(indiceActual.sincronizar(fragmentos).block(Duration.ofSeconds(60)))
					.isEqualTo(new ResultadoSincronizacion(0, 7, 0, 0));
		}
	}

}
