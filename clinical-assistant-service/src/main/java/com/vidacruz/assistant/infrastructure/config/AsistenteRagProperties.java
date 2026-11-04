package com.vidacruz.assistant.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * assistant.rag.*: parametros del flujo de consulta (RF-04 parte A).
 * <ul>
 * <li>{@code topK} / {@code similarityThreshold}: barrera 1 (cuantos fragmentos y similitud minima, 0..1).</li>
 * <li>{@code mensajeSinProtocolo}: texto exacto de la respuesta cuando no hay protocolo (RN-05).</li>
 * <li>{@code corpus}: patron de recursos con los protocolos (Markdown), p. ej. classpath:protocolos/*.md o file:/datos/*.md.</li>
 * <li>{@code ingestOnStartup}: indexar al arrancar; si es false se asume que el indice ya existe.</li>
 * <li>{@code modeloEmbeddings}: modelo con el que se calcularon los vectores del indice; entra en el hash de cada
 * fragmento, asi que cambiar de modelo (o de proveedor con otro nombre de modelo) reindexa solo.</li>
 * </ul>
 */
@ConfigurationProperties("assistant.rag")
public record AsistenteRagProperties(
		int topK,
		double similarityThreshold,
		String mensajeSinProtocolo,
		String corpus,
		boolean ingestOnStartup,
		String modeloEmbeddings,
		Indice indice,
		Ingesta ingesta,
		RetryAfter retryAfter) {

	/** Indice vectorial: nombre y prefijo de claves (propios, distintos de los de la vista de urgencias). */
	public record Indice(String nombre, String prefijo) {
	}

	/** Backoff de los reintentos de indexacion en segundo plano cuando el proveedor de embeddings o Redis fallan. */
	public record Ingesta(Duration backoffInicial, Duration backoffMaximo) {
	}

	/** Valor del header Retry-After de los 503. */
	public record RetryAfter(Duration proveedor, Duration indice) {
	}

}
