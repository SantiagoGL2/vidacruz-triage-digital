package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.OutboxEventoMapper;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.repository.OutboxEventoRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Outbox en PostgreSQL. La escritura usa el template (insert explicito, misma transaccion que el cambio); las
 * consultas (SKIP LOCKED, backoff, UPDATEs) son SQL estatico en {@link OutboxEventoRepository}.
 */
@Slf4j
@Component
class OutboxR2dbcAdapter implements OutboxRepositoryPort {

	private final R2dbcEntityTemplate template;
	private final OutboxEventoRepository repositorio;
	private final OutboxEventoMapper mapper;

	OutboxR2dbcAdapter(R2dbcEntityTemplate template, OutboxEventoRepository repositorio, OutboxEventoMapper mapper) {
		this.template = template;
		this.repositorio = repositorio;
		this.mapper = mapper;
	}

	@Override
	public Mono<Void> guardar(EventoTriaje evento) {
		return Mono.fromSupplier(() -> mapper.aEntidad(evento)).flatMap(template::insert).then();
	}

	@Override
	public Flux<EventoPendiente> reservarPendientes(int limite, Instant ahora, PoliticaReintentoOutbox politica) {
		return repositorio.reservarPendientes(politica.maxIntentos(), politica.backoffInicial().toMillis(),
						politica.backoffMaximo().toMillis(), ahora, limite)
				.map(p -> new EventoPendiente(p.id(), p.aggregateId(), p.eventType(), p.payload(), p.correlationId(),
						p.intentos()));
	}

	@Override
	public Mono<Void> marcarPublicado(UUID eventId, Instant cuando) {
		return repositorio.marcarPublicado(eventId, cuando)
				.filter(filas -> filas == 0)
				.doOnNext(filas -> log.warn("Evento {} publicado pero no se encontro en el outbox para marcarlo", eventId))
				.then();
	}

	@Override
	public Mono<Void> registrarFallo(UUID eventId, String motivo, Instant cuando) {
		return repositorio.registrarFallo(eventId, motivo, cuando).then();
	}

	@Override
	public Mono<Long> contarPendientes(int maxIntentos) {
		return repositorio.contarPendientes(maxIntentos);
	}

}
