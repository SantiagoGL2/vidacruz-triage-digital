package com.vidacruz.assistant.infrastructure.entrypoint.kafka.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.assistant.application.command.AplicarEventoComando;
import com.vidacruz.assistant.application.port.in.AplicarEventoClinicoUseCase;
import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.infrastructure.config.AsistenteKafkaProperties;
import com.vidacruz.assistant.infrastructure.entrypoint.kafka.dto.EventoTriajeMensaje;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * RF-04 parte B: consume {@code triaje.eventos} con su propio consumer group y aplica cada evento a la vista de
 * urgencias.
 * <p>
 * <b>Por que el listener espera al Mono en lugar de devolverlo.</b> Spring Kafka 3.3 admite listeners que devuelven
 * {@code Mono}, pero (verificado en los tests de este servicio) un Mono que falla <i>no</i> llega al
 * {@code DefaultErrorHandler}: se registra y el offset se confirma, o sea, el evento se pierde sin reintento ni DLT.
 * Ademas el contenedor puede entregar el registro siguiente sin esperar a que termine el anterior, con lo que un
 * reintento por Redis caido desordenaria los eventos de un mismo paciente (un {@code en_consulta} podria aplicarse
 * antes que su {@code registrado}). Por eso el procesamiento es secuencial por particion: se espera el resultado con un
 * {@code block(timeout)} <b>en el hilo del consumidor Kafka</b> (un hilo dedicado, nunca el event loop de Netty), y las
 * excepciones salen por la via normal: transitorias -> reintento con backoff sin confirmar el offset;
 * no recuperables -> DLT.
 * <p>
 * La deserializacion (JSON invalido) la absorbe el ErrorHandlingDeserializer; un evento incompleto lanza
 * {@link EventoInvalidoException}; ambos van directo a la DLT.
 */
@Component
class EventosTriajeKafkaListener {

	static final String HEADER_CORRELATION = "correlationId";

	/** Solo caracteres seguros: el valor termina en logs (evita inyeccion de lineas). */
	private static final Pattern CORRELACION_VALIDA = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final AplicarEventoClinicoUseCase aplicar;
	private final Duration timeout;

	EventosTriajeKafkaListener(AplicarEventoClinicoUseCase aplicar, AsistenteKafkaProperties kafka) {
		this.aplicar = aplicar;
		this.timeout = kafka.timeoutProcesamiento();
	}

	@KafkaListener(id = "estado-urgencias", idIsGroup = false,
			topics = "${assistant.kafka.topic}",
			groupId = "${assistant.kafka.group-id}",
			autoStartup = "${assistant.kafka.habilitado}")
	void alRecibir(ConsumerRecord<String, EventoTriajeMensaje> registro) {
		EventoTriajeMensaje evento = registro.value();
		if (evento == null) {
			throw new EventoInvalidoException("Mensaje sin cuerpo en triaje.eventos (offset " + registro.offset() + ")");
		}
		// block() relanza las RuntimeException tal cual (EventoInvalido, EstadoUrgenciasNoDisponible)
		aplicar.aplicar(comando(evento, correlationId(registro, evento))).block(timeout);
	}

	private static AplicarEventoComando comando(EventoTriajeMensaje e, String correlationId) {
		JsonNode payload = e.payload();
		return new AplicarEventoComando(e.eventId(), e.eventType(), e.occurredAt(), correlationId,
				texto(payload, "pacienteId"), texto(payload, "nivel"), texto(payload, "fechaRegistro"));
	}

	/** El header del mensaje manda (lo pone el publicador del outbox); si falta, el del envelope. */
	private static String correlationId(ConsumerRecord<String, EventoTriajeMensaje> registro, EventoTriajeMensaje evento) {
		Header header = registro.headers().lastHeader(HEADER_CORRELATION);
		if (header != null && header.value() != null) {
			String valor = new String(header.value(), StandardCharsets.UTF_8);
			if (CORRELACION_VALIDA.matcher(valor).matches()) {
				return valor;
			}
		}
		String enEnvelope = evento.correlationId();
		return enEnvelope != null && CORRELACION_VALIDA.matcher(enEnvelope).matches() ? enEnvelope : null;
	}

	private static String texto(JsonNode payload, String campo) {
		if (payload == null) {
			return null;
		}
		JsonNode nodo = payload.path(campo);
		return nodo.isMissingNode() || nodo.isNull() ? null : nodo.asText();
	}

}
