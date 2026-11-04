package com.vidacruz.assistant.infrastructure.drivenadapter.redis.indice;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore.MetadataField;
import redis.clients.jedis.JedisPooled;

/**
 * Define UNA vez el esquema del indice vectorial (campos de metadatos incluidos) para que el vector store de consulta y
 * el que crea el indice coincidan. Solo los campos declarados aqui se devuelven en las busquedas.
 */
public final class ConstructorIndice {

	public static final String CAMPO_PROTOCOLO = "protocolo";
	public static final String CAMPO_TITULO = "titulo";
	public static final String CAMPO_SECCION = "seccion";
	public static final String CAMPO_VERSION = "version";
	public static final String CAMPO_HASH = "hash";

	private ConstructorIndice() {
	}

	/** {@code crearEsquema = true}: al llamar {@code afterPropertiesSet()} crea el indice si no existe. */
	public static RedisVectorStore crear(JedisPooled jedis, EmbeddingModel embeddings, String nombre, String prefijo,
			boolean crearEsquema) {
		return RedisVectorStore.builder(jedis, embeddings)
				.indexName(nombre)
				.prefix(prefijo)
				.metadataFields(MetadataField.tag(CAMPO_PROTOCOLO), MetadataField.text(CAMPO_TITULO),
						MetadataField.text(CAMPO_SECCION), MetadataField.tag(CAMPO_VERSION), MetadataField.tag(CAMPO_HASH))
				.initializeSchema(crearEsquema)
				.build();
	}

}
