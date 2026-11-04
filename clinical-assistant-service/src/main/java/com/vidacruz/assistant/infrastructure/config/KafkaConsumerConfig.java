package com.vidacruz.assistant.infrastructure.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import com.vidacruz.assistant.infrastructure.entrypoint.kafka.dto.EventoTriajeMensaje;
import com.vidacruz.assistant.domain.exception.EstadoUrgenciasNoDisponibleException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumo tolerante de {@code triaje.eventos}.
 * <ul>
 * <li><b>Deserializacion:</b> {@link ErrorHandlingDeserializer} envuelve al deserializador JSON. Un mensaje ilegible
 * no lanza dentro del poll (que detendria el consumo): llega al error handler como DeserializationException.</li>
 * <li><b>Errores transitorios</b> (Redis caido): reintento con backoff exponencial; si se agota, DLT.</li>
 * <li><b>No recuperables</b> (deserializacion, {@link EventoInvalidoException}): directo a la DLT, sin reintentos.</li>
 * <li>Cada mensaje enviado a la DLT cuenta en la metrica {@code asistente.eventos.dlt}.</li>
 * </ul>
 * Boot aplica el {@link DefaultErrorHandler} y el {@link ConsumerFactory} a la fabrica de contenedores de @KafkaListener.
 */
@Configuration
class KafkaConsumerConfig {

	/**
	 * Tipado {@code <Object, Object>} a proposito: la fabrica de contenedores de Boot busca un
	 * {@code ConsumerFactory<Object, Object>}; con otros genericos no lo encuentra y usaria un consumidor de Strings.
	 */
	@Bean
	@SuppressWarnings("unchecked")
	ConsumerFactory<Object, Object> consumerFactory(KafkaProperties kafka) {
		var mapper = JsonMapper.builder().findAndAddModules()
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
		// useHeadersIfPresent=false: el tipo es siempre el envelope, no el que diga un header del productor
		var json = new JsonDeserializer<>(EventoTriajeMensaje.class, mapper, false);
		return new DefaultKafkaConsumerFactory<>(kafka.buildConsumerProperties(null),
				(Deserializer<Object>) (Deserializer<?>) new StringDeserializer(),
				(Deserializer<Object>) (Deserializer<?>) new ErrorHandlingDeserializer<>(json));
	}

	/** Plantilla para republicar en la DLT los mensajes que no se pudieron deserializar: se reenvian los bytes originales. */
	@Bean
	KafkaTemplate<String, byte[]> dltBytesTemplate(KafkaProperties kafka) {
		return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(kafka.buildProducerProperties(null),
				new StringSerializer(), new ByteArraySerializer()));
	}

	/** Plantilla para los mensajes bien deserializados que fallaron despues (evento incompleto, Redis agotado). */
	@Bean
	KafkaTemplate<String, EventoTriajeMensaje> dltEventoTemplate(KafkaProperties kafka) {
		var serializador = new JsonSerializer<EventoTriajeMensaje>(JsonMapper.builder().findAndAddModules().build());
		serializador.setAddTypeInfo(false);
		return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(kafka.buildProducerProperties(null),
				new StringSerializer(), serializador));
	}

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, byte[]> dltBytesTemplate,
			KafkaTemplate<String, EventoTriajeMensaje> dltEventoTemplate, AsistenteKafkaProperties kafka,
			MetricasPort metricas) {
		// El DLPR elige la plantilla por la clase del valor: byte[] = fallo de deserializacion, Void = valor nulo.
		Map<Class<?>, KafkaOperations<?, ?>> plantillas = new LinkedHashMap<>();
		plantillas.put(EventoTriajeMensaje.class, dltEventoTemplate);
		plantillas.put(byte[].class, dltBytesTemplate);
		plantillas.put(Void.class, dltBytesTemplate);
		var dlt = new DeadLetterPublishingRecoverer(plantillas,
				(registro, error) -> new TopicPartition(kafka.dltTopic(), -1));
		ConsumerRecordRecoverer recuperador = (registro, error) -> {
			metricas.eventoEnviadoADlt();
			dlt.accept(registro, error);
		};

		var retry = kafka.retry();
		var backoff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
		backoff.setMaxInterval(retry.maxInterval().toMillis());
		backoff.setMaxElapsedTime(retry.maxElapsedTime().toMillis());
		var handler = new DefaultErrorHandler(recuperador, backoff);
		handler.addNotRetryableExceptions(EventoInvalidoException.class);
		// Dejar claro que Redis caido SI se reintenta (es el caso para el que existe el backoff)
		handler.removeClassification(EstadoUrgenciasNoDisponibleException.class);
		return handler;
	}

}
