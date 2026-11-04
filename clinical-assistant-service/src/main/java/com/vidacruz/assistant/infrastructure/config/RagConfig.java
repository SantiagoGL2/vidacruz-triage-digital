package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.application.port.in.ConsultarProtocoloUseCase;
import com.vidacruz.assistant.application.port.in.IndexarProtocolosUseCase;
import com.vidacruz.assistant.application.usecase.ConsultarProtocoloUseCaseImpl;
import com.vidacruz.assistant.application.usecase.IndexarProtocolosUseCaseImpl;
import com.vidacruz.assistant.domain.model.ParametrosRecuperacion;
import com.vidacruz.assistant.domain.port.out.BuscadorProtocolosPort;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.port.out.GeneradorRespuestaPort;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import com.vidacruz.assistant.domain.service.FragmentadorProtocolos;
import com.vidacruz.assistant.domain.service.LectorProtocolos;
import com.vidacruz.assistant.domain.service.ValidadorRespuesta;
import com.vidacruz.assistant.infrastructure.drivenadapter.redis.indice.ConstructorIndice;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * Cableado del RAG. El vector store se arma a mano (no con la autoconfiguracion de Spring AI para Redis, que en 1.1 exige
 * un JedisConnectionFactory, no declara los campos de metadatos que necesitamos recuperar y crea el indice en el arranque,
 * con lo que Redis o el proveedor de embeddings caidos tumbarian la aplicacion). Aqui NO se crea el indice: lo hace la
 * indexacion en segundo plano, con reintentos.
 */
@Configuration
public class RagConfig {

	@Bean(destroyMethod = "close")
	JedisPooled jedisPooled(RedisProperties redis) {
		var cliente = DefaultJedisClientConfig.builder()
				.password(redis.getPassword() == null || redis.getPassword().isBlank() ? null : redis.getPassword())
				.timeoutMillis((int) redis.getTimeout().toMillis())
				.connectionTimeoutMillis((int) redis.getConnectTimeout().toMillis())
				.build();
		return new JedisPooled(new HostAndPort(redis.getHost(), redis.getPort()), cliente);
	}

	@Bean
	VectorStore vectorStore(JedisPooled jedis, EmbeddingModel embeddings, AsistenteRagProperties rag) {
		return ConstructorIndice.crear(jedis, embeddings, rag.indice().nombre(), rag.indice().prefijo(), false);
	}

	@Bean
	LectorProtocolos lectorProtocolos() {
		return new LectorProtocolos();
	}

	@Bean
	FragmentadorProtocolos fragmentadorProtocolos() {
		return new FragmentadorProtocolos();
	}

	@Bean
	ValidadorRespuesta validadorRespuesta() {
		return new ValidadorRespuesta();
	}

	@Bean
	IndexarProtocolosUseCase indexarProtocolosUseCase(FuenteProtocolosPort fuente, FragmentadorProtocolos fragmentador,
			IndiceProtocolosPort indice, EstadoIndicePort estado) {
		return new IndexarProtocolosUseCaseImpl(fuente, fragmentador, indice, estado);
	}

	@Bean
	ConsultarProtocoloUseCase consultarProtocoloUseCase(BuscadorProtocolosPort buscador, GeneradorRespuestaPort generador,
			ValidadorRespuesta validador, EstadoIndicePort estado, MetricasRagPort metricas, AsistenteRagProperties rag) {
		return new ConsultarProtocoloUseCaseImpl(buscador, generador, validador, estado, metricas,
				new ParametrosRecuperacion(rag.topK(), rag.similarityThreshold()), rag.mensajeSinProtocolo());
	}

}
