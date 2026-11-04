package com.vidacruz.assistant.support;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Contenedores compartidos por toda la JVM de tests (patron singleton): arrancan la primera vez que se piden
 * y los detiene Ryuk al terminar. Asi no se levanta un Redis/Kafka por clase de test.
 */
public final class Contenedores {

	public static final String TOPIC = "triaje.eventos";
	public static final String TOPIC_DLT = "triaje.eventos.DLT";
	public static final String PASSWORD_REDIS = "test-only";

	private Contenedores() {
	}

	private static final class KafkaHolder {
		static final KafkaContainer INSTANCIA = iniciar();

		private static KafkaContainer iniciar() {
			var contenedor = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));
			contenedor.start();
			// 1 particion: el orden y el lag son faciles de razonar en los tests
			try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", contenedor.getBootstrapServers()))) {
				admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(TOPIC_DLT, 1, (short) 1)))
						.all().get();
			}
			catch (Exception e) {
				throw new IllegalStateException("No se pudieron crear los topics de prueba", e);
			}
			return contenedor;
		}
	}

	private static final class RedisHolder {
		static final GenericContainer<?> INSTANCIA = iniciar();

		private static GenericContainer<?> iniciar() {
			var contenedor = new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v3"))
					.withExposedPorts(6379)
					.withEnv("REDIS_ARGS", "--requirepass " + PASSWORD_REDIS)
					.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
			contenedor.start();
			return contenedor;
		}
	}

	public static KafkaContainer kafka() {
		return KafkaHolder.INSTANCIA;
	}

	public static GenericContainer<?> redis() {
		return RedisHolder.INSTANCIA;
	}

}
