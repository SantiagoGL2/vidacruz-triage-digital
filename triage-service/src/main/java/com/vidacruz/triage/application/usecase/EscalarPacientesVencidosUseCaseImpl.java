package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.application.port.in.EscalarPacientesVencidosUseCase;
import com.vidacruz.triage.application.result.ResultadoEscalamiento;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.PacienteVersionado;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * RF-03 / RN-03. La decision de escalar es del dominio ({@code Paciente.escalar}); la garantia ante concurrencia y
 * ante varias replicas es el UPDATE condicional atomico: solo quien afecta 1 fila audita y emite el evento.
 * Cada paciente va en su propia transaccion y un fallo con uno no impide procesar a los demas.
 */
@Slf4j
public class EscalarPacientesVencidosUseCaseImpl implements EscalarPacientesVencidosUseCase {

	private enum Estado { ESCALADO, OMITIDO, FALLIDO }

	private record Resultado(Estado estado, NivelTriaje nivel) {
	}

	private final PacienteRepositoryPort pacientes;
	private final RegistroCambioClinico registroCambio;
	private final TransaccionPort transaccion;
	private final MetricasPort metricas;
	private final Clock clock;
	private final int lote;

	public EscalarPacientesVencidosUseCaseImpl(PacienteRepositoryPort pacientes, AuditoriaRepositoryPort auditoria,
			OutboxRepositoryPort outbox, FabricaEventos fabricaEventos, TransaccionPort transaccion, MetricasPort metricas,
			Clock clock, int lote) {
		this.pacientes = pacientes;
		this.registroCambio = new RegistroCambioClinico(auditoria, outbox, fabricaEventos);
		this.transaccion = transaccion;
		this.metricas = metricas;
		this.clock = clock;
		this.lote = lote;
	}

	@Override
	public Mono<ResultadoEscalamiento> escalarVencidos() {
		return Mono.defer(() -> {
			Instant ahora = clock.instant();
			// Se leen primero todos los ids (se libera la conexion) y luego cada paciente abre su propia transaccion.
			return pacientes.buscarIdsConSlaVencido(ahora, lote).collectList()
					.flatMapMany(reactor.core.publisher.Flux::fromIterable)
					.concatMap(id -> escalar(id, ahora))
					.collectList()
					.map(resultados -> resumir(resultados.stream().map(Resultado::estado).toList()));
		});
	}

	private Mono<Resultado> escalar(UUID id, Instant ahora) {
		return transaccion.enTransaccion(
				pacientes.buscarPorId(id)
						.flatMap(actual -> decidirYAplicar(actual, ahora))
						.defaultIfEmpty(new Resultado(Estado.OMITIDO, null)))
				.doOnNext(r -> {
					if (r.estado() == Estado.ESCALADO) {
						// Solo tras el commit.
						metricas.pacienteEscalado(r.nivel());
						log.info("Paciente id={} nivel={} escalado por SLA vencido", id, r.nivel());
					}
				})
				// Fuera de la transaccion: ya hizo rollback y el fallo de este paciente no corta el ciclo.
				.onErrorResume(e -> {
					log.warn("No se pudo escalar al paciente id={}: {}", id, e.toString());
					return Mono.just(new Resultado(Estado.FALLIDO, null));
				});
	}

	private Mono<Resultado> decidirYAplicar(PacienteVersionado actual, Instant ahora) {
		return actual.paciente().escalar(ahora)
				.map(cambio -> aplicar(cambio))
				.orElseGet(() -> Mono.just(new Resultado(Estado.OMITIDO, null)));
	}

	private Mono<Resultado> aplicar(CambioClinico cambio) {
		return pacientes.marcarEscalado(cambio.paciente().id(), cambio.paciente().fechaEscalamiento())
				.flatMap(afectado -> afectado
						? registroCambio.registrar(cambio).thenReturn(new Resultado(Estado.ESCALADO, cambio.paciente().nivel()))
						: Mono.just(new Resultado(Estado.OMITIDO, null)));
	}

	private static ResultadoEscalamiento resumir(java.util.List<Estado> estados) {
		int escalados = (int) estados.stream().filter(e -> e == Estado.ESCALADO).count();
		int fallidos = (int) estados.stream().filter(e -> e == Estado.FALLIDO).count();
		return new ResultadoEscalamiento(estados.size(), escalados, estados.size() - escalados - fallidos, fallidos);
	}

}
