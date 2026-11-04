package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.command.NotificarEscalamientoComando;
import com.vidacruz.triage.application.port.in.NotificarEscalamientoUseCase;
import com.vidacruz.triage.domain.model.Alerta;
import com.vidacruz.triage.domain.port.out.DeduplicacionEventosPort;
import com.vidacruz.triage.domain.port.out.NotificadorAlertasPort;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RF-06. Idempotente por eventId. Cada canal se aisla del resto: si el webhook cae, el SSE igual recibe la alerta.
 * Nunca se loguea el nombre del paciente (ni siquiera lo recibe).
 */
@Slf4j
public class NotificarEscalamientoUseCaseImpl implements NotificarEscalamientoUseCase {

	private final DeduplicacionEventosPort deduplicacion;
	private final List<NotificadorAlertasPort> canales;

	public NotificarEscalamientoUseCaseImpl(DeduplicacionEventosPort deduplicacion, List<NotificadorAlertasPort> canales) {
		this.deduplicacion = deduplicacion;
		this.canales = List.copyOf(canales);
	}

	@Override
	public Mono<Void> notificar(NotificarEscalamientoComando c) {
		return deduplicacion.registrarSiNuevo(c.eventId())
				.flatMap(nuevo -> {
					if (!nuevo) {
						log.debug("Evento {} ya procesado: se ignora el duplicado", c.eventId());
						return Mono.<Void>empty();
					}
					Alerta alerta = new Alerta(c.eventId(), c.pacienteId(), c.nivel(),
							Duration.between(c.fechaRegistro(), c.ocurridoEn()).toMinutes(), c.slaMinutos(),
							c.fechaRegistro(), c.ocurridoEn(), c.correlationId());
					log.info("Alerta de escalamiento paciente={} nivel={} esperaMin={} slaMin={}", alerta.pacienteId(),
							alerta.nivel(), alerta.minutosEspera(), alerta.slaMinutos());
					return Flux.fromIterable(canales)
							.flatMap(canal -> canal.notificar(alerta).onErrorResume(e -> {
								log.warn("Canal {} fallo al notificar paciente={}: {}", canal.getClass().getSimpleName(),
										alerta.pacienteId(), e.toString());
								return Mono.empty();
							}))
							.then();
				});
	}

}
