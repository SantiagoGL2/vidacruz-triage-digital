package com.vidacruz.triage.infrastructure.entrypoint.kafka.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.application.command.NotificarEscalamientoComando;
import com.vidacruz.triage.application.context.ContextoCorrelacion;
import com.vidacruz.triage.application.port.in.NotificarEscalamientoUseCase;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import com.vidacruz.triage.infrastructure.entrypoint.kafka.exception.EventoInvalidoException;
import java.time.Duration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * RF-06: consume {@code triaje.eventos} con su propio consumer group y procesa solo {@code paciente_escalado}.
 * <p>
 * El procesamiento es <b>sincrono por registro</b> (ADR 0004): el hilo del consumidor Kafka (dedicado, nunca el event
 * loop) espera el resultado del caso de uso con {@code block(timeout)}. Asi el offset se confirma solo al terminar y
 * cualquier error, incluido uno inesperado dentro del caso de uso, llega al {@code DefaultErrorHandler}: se reintenta
 * con backoff y, agotados los reintentos, va a la DLT. (Un listener que devuelve {@code Mono} pierde los errores que
 * ocurren dentro del Mono en spring-kafka 3.3.) Un mensaje ilegible lanza {@link EventoInvalidoException}: no se
 * reintenta y va directo a la DLT.
 */
@Component
class EscalamientoKafkaListener {

	static final String TIPO_ESCALADO = "paciente_escalado";

	private final NotificarEscalamientoUseCase notificar;
	private final ObjectMapper objectMapper;
	private final Duration timeout;

	EscalamientoKafkaListener(NotificarEscalamientoUseCase notificar, ObjectMapper objectMapper,
			TriageAlertasProperties alertas) {
		this.notificar = notificar;
		this.objectMapper = objectMapper;
		this.timeout = alertas.consumidor().timeoutProcesamiento();
	}

	@KafkaListener(id = "alertas-escalamiento", idIsGroup = false,
			topics = "${triage.kafka.topic}",
			groupId = "${triage.alertas.consumidor.group-id}",
			autoStartup = "${triage.alertas.consumidor.habilitado}")
	void alRecibir(String mensaje) {
		EventoTriaje evento = leer(mensaje);
		if (!TIPO_ESCALADO.equals(evento.eventType())) {
			return;
		}
		// block() relanza las RuntimeException tal cual; un timeout lanza IllegalStateException (se reintenta)
		notificar.notificar(comando(evento))
				.contextWrite(ctx -> ctx.put(ContextoCorrelacion.CLAVE,
						evento.correlationId() == null ? ContextoCorrelacion.SIN_CORRELACION : evento.correlationId()))
				.block(timeout);
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
