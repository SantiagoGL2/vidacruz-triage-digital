package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Outbox transaccional: escritura junto al cambio de estado y lectura/marcado para el publicador. */
public interface OutboxRepositoryPort {

	/** Escribe el evento en la misma transaccion que el cambio; la publicacion a Kafka la hace el publicador. */
	Mono<Void> guardar(EventoTriaje evento);

	/**
	 * Reserva hasta {@code limite} eventos publicables (sin publicar, con intentos disponibles y fuera de su
	 * backoff) con {@code FOR UPDATE SKIP LOCKED}: debe ejecutarse dentro de una transaccion, que mantiene el candado.
	 * Por paciente solo entrega el evento mas antiguo pendiente, para no alterar el orden por paciente.
	 */
	Flux<EventoPendiente> reservarPendientes(int limite, Instant ahora, PoliticaReintentoOutbox politica);

	Mono<Void> marcarPublicado(UUID eventId, Instant cuando);

	/** Suma un intento fallido y guarda el motivo; el evento sigue pendiente. */
	Mono<Void> registrarFallo(UUID eventId, String motivo, Instant cuando);

	/** Eventos sin publicar que aun tienen intentos disponibles. */
	Mono<Long> contarPendientes(int maxIntentos);

}
