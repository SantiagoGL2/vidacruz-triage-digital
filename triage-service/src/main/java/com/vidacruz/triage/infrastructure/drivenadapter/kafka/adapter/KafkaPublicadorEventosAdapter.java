package com.vidacruz.triage.infrastructure.drivenadapter.kafka.adapter;

import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.port.out.PublicadorEventosPort;
import com.vidacruz.triage.infrastructure.config.TriageKafkaProperties;
import com.vidacruz.triage.infrastructure.config.TriageOutboxProperties;
import com.vidacruz.triage.infrastructure.drivenadapter.kafka.CabecerasKafka;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publica en {@code triaje.eventos}: key = pacienteId (orden por paciente en la particion), value = el envelope
 * guardado en el outbox y headers eventId / eventType / correlationId. El producer es idempotente con acks=all
 * (application.yml). {@code KafkaTemplate.send} puede bloquear (metadata, buffer), por eso se invoca en
 * boundedElastic y se envuelve con {@code Mono.fromFuture}.
 */
@Component
class KafkaPublicadorEventosAdapter implements PublicadorEventosPort {

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final TriageKafkaProperties kafka;
	private final TriageOutboxProperties outbox;

	KafkaPublicadorEventosAdapter(KafkaTemplate<String, String> kafkaTemplate, TriageKafkaProperties kafka,
			TriageOutboxProperties outbox) {
		this.kafkaTemplate = kafkaTemplate;
		this.kafka = kafka;
		this.outbox = outbox;
	}

	@Override
	public Mono<Void> publicar(EventoPendiente evento) {
		return Mono.fromFuture(() -> kafkaTemplate.send(registro(evento)))
				.subscribeOn(Schedulers.boundedElastic())
				.timeout(outbox.timeoutPublicacion())
				.then();
	}

	private ProducerRecord<String, String> registro(EventoPendiente evento) {
		var registro = new ProducerRecord<String, String>(kafka.topic(), evento.aggregateId().toString(), evento.payload());
		registro.headers().add(CabecerasKafka.EVENT_ID, bytes(evento.eventId().toString()));
		registro.headers().add(CabecerasKafka.EVENT_TYPE, bytes(evento.eventType()));
		registro.headers().add(CabecerasKafka.CORRELATION_ID, bytes(evento.correlationId() == null ? "" : evento.correlationId()));
		return registro;
	}

	private static byte[] bytes(String valor) {
		return valor.getBytes(StandardCharsets.UTF_8);
	}

}
