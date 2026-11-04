package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.OutboxEventoMapper;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Escribe el evento en outbox_eventos (misma transaccion que el cambio); la publicacion a Kafka es otro componente. */
@Component
class OutboxR2dbcAdapter implements OutboxRepositoryPort {

	private final R2dbcEntityTemplate template;
	private final OutboxEventoMapper mapper;

	OutboxR2dbcAdapter(R2dbcEntityTemplate template, OutboxEventoMapper mapper) {
		this.template = template;
		this.mapper = mapper;
	}

	@Override
	public Mono<Void> guardar(EventoTriaje evento) {
		return Mono.fromSupplier(() -> mapper.aEntidad(evento)).flatMap(template::insert).then();
	}

}
