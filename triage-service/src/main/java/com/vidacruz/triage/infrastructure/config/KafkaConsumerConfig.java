package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.infrastructure.entrypoint.kafka.exception.EventoInvalidoException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Manejo de errores del consumidor de alertas: reintentos con backoff exponencial y, agotados, el mensaje va a
 * {@code triaje.eventos.DLT}. Los mensajes ilegibles ({@link EventoInvalidoException}) y los fallos de
 * deserializacion de spring-kafka se consideran NO recuperables: van directo a la DLT sin reintentos.
 * Boot aplica este bean a la fabrica de contenedores de los @KafkaListener.
 */
@Configuration
class KafkaConsumerConfig {

	/** La DLT es el topic de eventos + este sufijo (triaje.eventos -> triaje.eventos.DLT). */
	static final String SUFIJO_DLT = ".DLT";

	@Bean
	DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template, TriageKafkaProperties kafka,
			TriageAlertasProperties alertas) {
		var recoverer = new DeadLetterPublishingRecoverer(template,
				(registro, error) -> new TopicPartition(kafka.topic() + SUFIJO_DLT, -1));
		var consumidor = alertas.consumidor();
		var backoff = new ExponentialBackOff(consumidor.backoffInicial().toMillis(), 2.0);
		backoff.setMaxInterval(consumidor.backoffMaximo().toMillis());
		backoff.setMaxAttempts(consumidor.reintentos());
		var handler = new DefaultErrorHandler(recoverer, backoff);
		handler.addNotRetryableExceptions(EventoInvalidoException.class);
		return handler;
	}

}
