package com.vidacruz.assistant.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Productor, lector de la DLT y consulta de lag contra el Kafka compartido. */
public final class KafkaDePrueba {

	private KafkaDePrueba() {
	}

	private static String bootstrap() {
		return Contenedores.kafka().getBootstrapServers();
	}

	/** Publica un mensaje en triaje.eventos. {@code correlationId} opcional (header, como hace el outbox de triage). */
	public static void publicar(String clave, String valor, String correlationId) {
		Map<String, Object> props = Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		try (var productor = new KafkaProducer<String, String>(props)) {
			var registro = new ProducerRecord<>(Contenedores.TOPIC, clave, valor);
			if (correlationId != null) {
				registro.headers().add(new RecordHeader("correlationId", correlationId.getBytes(StandardCharsets.UTF_8)));
			}
			productor.send(registro).get(10, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IllegalStateException("No se pudo publicar en Kafka", e);
		}
	}

	/** Publica un evento con cabeceras adicionales (p. ej. {@code traceparent}, como hace el outbox de triage). */
	public static void publicar(EventosTriaje.Evento evento, Map<String, String> cabeceras) {
		Map<String, Object> props = Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		try (var productor = new KafkaProducer<String, String>(props)) {
			var registro = new ProducerRecord<>(Contenedores.TOPIC, evento.pacienteId().toString(), evento.json());
			cabeceras.forEach((k, v) -> registro.headers().add(new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8))));
			productor.send(registro).get(10, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IllegalStateException("No se pudo publicar en Kafka", e);
		}
	}

	public static void publicar(EventosTriaje.Evento evento) {
		publicar(evento.pacienteId().toString(), evento.json(), null);
	}

	/** Lector de la DLT con un grupo propio, desde el principio. Cerrar con try-with-resources. */
	public static final class LectorDlt implements AutoCloseable {

		private final KafkaConsumer<String, String> consumidor;
		private final List<ConsumerRecord<String, String>> leidos = new ArrayList<>();

		public LectorDlt() {
			Map<String, Object> props = new HashMap<>();
			props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
			props.put(ConsumerConfig.GROUP_ID_CONFIG, "lector-dlt-" + UUID.randomUUID());
			props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
			props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
			props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
			this.consumidor = new KafkaConsumer<>(props);
			this.consumidor.subscribe(List.of(Contenedores.TOPIC_DLT));
		}

		/** Lee durante {@code ventana} y devuelve todo lo acumulado desde que se abrio el lector. */
		public List<ConsumerRecord<String, String>> leer(Duration ventana) {
			long fin = System.nanoTime() + ventana.toNanos();
			do {
				consumidor.poll(Duration.ofMillis(300)).forEach(leidos::add);
			}
			while (System.nanoTime() < fin);
			return List.copyOf(leidos);
		}

		/** Espera hasta que aparezca en la DLT un mensaje con esa clave. */
		public ConsumerRecord<String, String> esperarClave(String clave, Duration maximo) {
			long fin = System.nanoTime() + maximo.toNanos();
			while (System.nanoTime() < fin) {
				for (var r : leer(Duration.ofMillis(300))) {
					if (clave.equals(r.key())) {
						return r;
					}
				}
			}
			throw new AssertionError("No llego a la DLT ningun mensaje con clave " + clave + ". En la DLT hay: "
					+ leidos.stream().map(r -> r.key() + " -> " + r.value()).toList());
		}

		@Override
		public void close() {
			consumidor.close(Duration.ofSeconds(2));
		}

	}

	/** Mensajes de triaje.eventos que el grupo aun no confirmo (suma de lag de todas las particiones). */
	public static long lag(String grupo) {
		Map<String, Object> adminProps = Map.of("bootstrap.servers", bootstrap());
		try (AdminClient admin = AdminClient.create(adminProps);
				var consultaFin = new KafkaConsumer<String, String>(Map.of(
						ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
						ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
						ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
			Map<TopicPartition, OffsetAndMetadata> confirmados = admin.listConsumerGroupOffsets(grupo)
					.partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
			var particiones = consultaFin.partitionsFor(Contenedores.TOPIC).stream()
					.map(p -> new TopicPartition(Contenedores.TOPIC, p.partition())).toList();
			Map<TopicPartition, Long> finales = consultaFin.endOffsets(particiones);
			long lag = 0;
			for (var tp : particiones) {
				OffsetAndMetadata c = confirmados.get(tp);
				lag += finales.get(tp) - (c == null ? 0 : c.offset());
			}
			return lag;
		}
		catch (Exception e) {
			throw new IllegalStateException("No se pudo calcular el lag", e);
		}
	}

}
