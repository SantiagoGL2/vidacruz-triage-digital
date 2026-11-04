package com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.OutboxEventoEntity;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Component;

/** Evento -> fila del outbox. El id de la fila es el eventId del envelope; el envelope completo va como JSONB. */
@Component
public class OutboxEventoMapper {

	private final ObjectMapper objectMapper;

	OutboxEventoMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public OutboxEventoEntity aEntidad(EventoTriaje evento) {
		try {
			return OutboxEventoEntity.builder()
					.id(evento.eventId())
					.aggregateId(evento.payload().pacienteId())
					.eventType(evento.eventType())
					.payload(Json.of(objectMapper.writeValueAsString(evento)))
					.createdAt(evento.occurredAt())
					.intentos(0)
					.build();
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("No se pudo serializar el evento " + evento.eventId(), e);
		}
	}

}
