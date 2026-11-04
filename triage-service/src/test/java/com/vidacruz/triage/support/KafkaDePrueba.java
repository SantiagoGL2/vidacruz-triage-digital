package com.vidacruz.triage.support;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Broker Kafka propio de una clase de test (Testcontainers) que se puede "caer" y "volver": pausa y reanuda el
 * contenedor, conservando su puerto. Sirve para probar el comportamiento del publicador con Kafka caido.
 */
public final class KafkaDePrueba {

	private final KafkaContainer contenedor = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

	public KafkaDePrueba() {
		contenedor.start();
	}

	public String bootstrapServers() {
		return contenedor.getBootstrapServers();
	}

	/** Kafka deja de responder (los clientes ven timeouts, no conexion rechazada). */
	public void caer() {
		contenedor.getDockerClient().pauseContainerCmd(contenedor.getContainerId()).exec();
	}

	public void volver() {
		contenedor.getDockerClient().unpauseContainerCmd(contenedor.getContainerId()).exec();
	}

}
