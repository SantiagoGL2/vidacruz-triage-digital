package com.vidacruz.triage.infrastructure.entrypoint.kafka.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.application.command.NotificarEscalamientoComando;
import com.vidacruz.triage.application.context.ContextoCorrelacion;
import com.vidacruz.triage.application.port.in.NotificarEscalamientoUseCase;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.infrastructure.entrypoint.kafka.exception.EventoInvalidoException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * RF-06: consume {@code triaje.eventos} con su propio consumer group y procesa solo {@code paciente_escalado}.
 * El metodo devuelve un Mono (spring-kafka lo espera y hace el ack al completar), sin bloquear el hilo del consumidor.
 * Un mensaje ilegible lanza {@link EventoInvalidoException}: no se reintenta y el DefaultErrorHandler lo manda a la DLT.
 */
@Component
class EscalamientoKafkaListener {

	static final String TIPO_ESCALADO = "paciente_escalado";

	private final NotificarEscalamientoUseCase notificar;
	private final ObjectMapper objectMapper;

	EscalamientoKafkaListener(NotificarEscalamientoUseCase notificar, ObjectMapper objectMapper) {
		this.notificar = notificar;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(id = "alertas-escalamiento", idIsGroup = false,
			topics = "${triage.kafka.topic}",
			groupId = "${triage.alertas.consumidor.group-id}",
			autoStartup = "${triage.alertas.consumidor.habilitado}")
	Mono<Void> alRecibir(String mensaje) {
		EventoTriaje evento = leer(mensaje);
		if (!TIPO_ESCALADO.equals(evento.eventType())) {
			return Mono.empty();
		}
		return notificar.notificar(comando(evento))
				.contextWrite(ctx -> ctx.put(ContextoCorrelacion.CLAVE,
						evento.correlationId() == null ? ContextoCorrelacion.SIN_CORRELACION : evento.correlationId()));
	}

	private EventoTriaje leer(String mensaje) {
		try {
			EventoTriaje evento = objectMapper.readValue(mensaje, EventoTriaje.class);
			if (evento == null || evento.eventId() == null || evento.eventType() == null || evento.occurredAt() == null
					|| evento.payload() == null) {
				throw new EventoInvalidoException("Evento mal formado: faltan eventId, eventType, occurredAt o payload");
			}
			return evento;
		}
		catch (JsonProcessingException e) {
			throw new EventoInvalidoException("JSON invalido en triaje.eventos", e);
		}
	}

	private NotificarEscalamientoComando comando(EventoTriaje evento) {
		var p = evento.payload();
		if (p.pacienteId() == null || p.nivel() == null || p.fechaRegistro() == null) {
			throw new EventoInvalidoException("paciente_escalado sin pacienteId, nivel o fechaRegistro");
		}
		try {
			return new NotificarEscalamientoComando(evento.eventId(), p.pacienteId(), NivelTriaje.valueOf(p.nivel()),
					p.slaMinutos(), p.fechaRegistro(), evento.occurredAt(), evento.correlationId());
		}
		catch (IllegalArgumentException e) {
			throw new EventoInvalidoException("Nivel desconocido: " + p.nivel(), e);
		}
	}

}
