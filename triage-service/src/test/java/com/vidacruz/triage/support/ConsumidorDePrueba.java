package com.vidacruz.triage.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Consumidor crudo para que los tests lean un topic desde el principio con un consumer group propio. */
public final class ConsumidorDePrueba implements AutoCloseable {

	private final KafkaConsumer<String, String> consumidor;

	public ConsumidorDePrueba(String bootstrapServers, String topic) {
		consumidor = new KafkaConsumer<>(Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
				new StringDeserializer(), new StringDeserializer());
		consumidor.subscribe(List.of(topic));
	}

	/** Lee hasta que {@code suficiente} se cumpla sobre lo acumulado o venza el plazo; devuelve lo leido. */
	public List<ConsumerRecord<String, String>> leerHasta(Duration plazo,
			Predicate<List<ConsumerRecord<String, String>>> suficiente) {
		List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
		long limite = System.nanoTime() + plazo.toNanos();
		while (System.nanoTime() < limite && !suficiente.test(leidos)) {
			consumidor.poll(Duration.ofMillis(300)).forEach(leidos::add);
		}
		return leidos;
	}

	public static String cabecera(ConsumerRecord<String, String> registro, String nombre) {
		var cabecera = registro.headers().lastHeader(nombre);
		return cabecera == null ? null : new String(cabecera.value(), java.nio.charset.StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		consumidor.close(Duration.ofSeconds(2));
	}

}
