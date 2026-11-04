package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.application.result.ResultadoPublicacion;
import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PublicadorEventosPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Publicador del Transactional Outbox. Todo el ciclo va en UNA transaccion: el {@code FOR UPDATE SKIP LOCKED} de la
 * lectura mantiene los eventos reservados mientras se publican, asi dos replicas nunca publican el mismo.
 * Un fallo de Kafka no es un error del ciclo: se anota (intentos + backoff) y el evento se reintenta despues; el
 * registro de pacientes nunca depende de esto.
 */
@Slf4j
public class PublicarEventosPendientesUseCaseImpl implements PublicarEventosPendientesUseCase {

	private enum Resultado { PUBLICADO, FALLIDO }

	private final OutboxRepositoryPort outbox;
	private final PublicadorEventosPort publicador;
	private final TransaccionPort transaccion;
	private final MetricasPort metricas;
	private final Clock clock;
	private final int lote;
	private final PoliticaReintentoOutbox politica;

	public PublicarEventosPendientesUseCaseImpl(OutboxRepositoryPort outbox, PublicadorEventosPort publicador,
			TransaccionPort transaccion, MetricasPort metricas, Clock clock, int lote, PoliticaReintentoOutbox politica) {
		this.outbox = outbox;
		this.publicador = publicador;
		this.transaccion = transaccion;
		this.metricas = metricas;
		this.clock = clock;
		this.lote = lote;
		this.politica = politica;
	}

	@Override
	public Mono<ResultadoPublicacion> publicarPendientes() {
		return transaccion.enTransaccion(Mono.defer(() ->
						outbox.reservarPendientes(lote, clock.instant(), politica).collectList().flatMap(this::publicarLote)))
				.flatMap(resultados -> outbox.contarPendientes(politica.maxIntentos()).map(pendientes -> {
					metricas.pendientesOutbox(pendientes);
					int publicados = (int) resultados.stream().filter(r -> r == Resultado.PUBLICADO).count();
					return new ResultadoPublicacion(publicados, resultados.size() - publicados, pendientes);
				}));
	}

	/** Los eventos del lote son de pacientes distintos (el repositorio entrega uno por paciente): se publican en paralelo. */
	private Mono<List<Resultado>> publicarLote(List<EventoPendiente> eventos) {
		return Flux.fromIterable(eventos).flatMap(this::publicarUno).collectList();
	}

	private Mono<Resultado> publicarUno(EventoPendiente evento) {
		return publicador.publicar(evento)
				.then(outbox.marcarPublicado(evento.eventId(), clock.instant()))
				.doOnSuccess(v -> metricas.eventoPublicado())
				.thenReturn(Resultado.PUBLICADO)
				.onErrorResume(e -> {
					metricas.fallaPublicacion();
					log.warn("No se pudo publicar el evento {} ({}) intento={}: {}", evento.eventId(), evento.eventType(),
							evento.intentos() + 1, e.toString());
					if (evento.intentos() + 1 >= politica.maxIntentos()) {
						log.error("El evento {} agoto sus {} intentos: queda sin publicar para revision manual",
								evento.eventId(), politica.maxIntentos());
					}
					return outbox.registrarFallo(evento.eventId(), motivo(e), clock.instant()).thenReturn(Resultado.FALLIDO);
				});
	}

	private static String motivo(Throwable e) {
		String texto = e.getClass().getSimpleName() + ": " + e.getMessage();
		return texto.length() > 500 ? texto.substring(0, 500) : texto;
	}

}
