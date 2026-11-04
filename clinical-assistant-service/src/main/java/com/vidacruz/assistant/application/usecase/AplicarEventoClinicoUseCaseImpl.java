package com.vidacruz.assistant.application.usecase;

import com.vidacruz.assistant.application.command.AplicarEventoComando;
import com.vidacruz.assistant.application.context.ContextoCorrelacion;
import com.vidacruz.assistant.application.port.in.AplicarEventoClinicoUseCase;
import com.vidacruz.assistant.application.result.ResultadoProcesamiento;
import com.vidacruz.assistant.domain.event.EventoClinico;
import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.domain.model.AplicacionEvento;
import com.vidacruz.assistant.domain.model.enums.TipoEventoClinico;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import com.vidacruz.assistant.domain.service.ProyectorUrgencias;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Orquesta: tipo conocido? -> validar (dominio) -> operacion (dominio) -> aplicar atomicamente (puerto) -> metricas.
 * Un tipo desconocido no es un error (compatibilidad hacia adelante): se ignora con log y metrica.
 */
public class AplicarEventoClinicoUseCaseImpl implements AplicarEventoClinicoUseCase {

	private static final Logger log = LoggerFactory.getLogger(AplicarEventoClinicoUseCaseImpl.class);

	private final ProyectorUrgencias proyector;
	private final EstadoUrgenciasPort estado;
	private final MetricasPort metricas;

	public AplicarEventoClinicoUseCaseImpl(ProyectorUrgencias proyector, EstadoUrgenciasPort estado, MetricasPort metricas) {
		this.proyector = proyector;
		this.estado = estado;
		this.metricas = metricas;
	}

	@Override
	public Mono<ResultadoProcesamiento> aplicar(AplicarEventoComando comando) {
		return Mono.defer(() -> {
			if (comando.eventType() == null || comando.eventType().isBlank()) {
				throw new EventoInvalidoException("Evento " + comando.eventId() + " sin eventType");
			}
			var tipo = TipoEventoClinico.desdeCodigo(comando.eventType());
			if (tipo.isEmpty()) {
				log.info("Evento {} ignorado: eventType desconocido '{}'", comando.eventId(), comando.eventType());
				metricas.eventoIgnorado("tipo_desconocido");
				return Mono.just(ResultadoProcesamiento.IGNORADO_TIPO_DESCONOCIDO);
			}
			EventoClinico evento = EventoClinico.crear(comando.eventId(), tipo.get(), comando.occurredAt(),
					comando.correlationId(), comando.pacienteId(), comando.nivel(), comando.fechaRegistro());
			return estado.aplicar(evento.eventId(), evento.ocurridoEn(), proyector.operacionPara(evento))
					.doOnNext(aplicacion -> metricas.vistaActualizada(aplicacion.vista()))
					.map(aplicacion -> registrar(evento, aplicacion));
		}).contextWrite(ctx -> ctx.hasKey(ContextoCorrelacion.CLAVE) || comando.correlationId() == null ? ctx
				: ctx.put(ContextoCorrelacion.CLAVE, comando.correlationId()));
	}

	private ResultadoProcesamiento registrar(EventoClinico evento, AplicacionEvento aplicacion) {
		String tipo = evento.tipo().codigo();
		return switch (aplicacion.resultado()) {
			case APLICADO -> {
				metricas.eventoProcesado(tipo);
				log.info("Evento {} {} aplicado (paciente {})", evento.eventId(), tipo, evento.pacienteId());
				yield ResultadoProcesamiento.APLICADO;
			}
			case SIN_CAMBIO -> {
				metricas.eventoProcesado(tipo);
				log.info("Evento {} {} valido sin efecto (paciente {})", evento.eventId(), tipo, evento.pacienteId());
				yield ResultadoProcesamiento.SIN_CAMBIO;
			}
			case DUPLICADO -> {
				metricas.eventoDuplicado();
				log.info("Evento {} duplicado: ya se habia procesado", evento.eventId());
				yield ResultadoProcesamiento.DUPLICADO;
			}
			case IGNORADO_PACIENTE_DESCONOCIDO -> {
				metricas.eventoIgnorado("paciente_desconocido");
				log.warn("Evento {} {} ignorado: el paciente {} no esta en la vista", evento.eventId(), tipo, evento.pacienteId());
				yield ResultadoProcesamiento.IGNORADO_PACIENTE_DESCONOCIDO;
			}
			case IGNORADO_YA_REGISTRADO -> {
				metricas.eventoIgnorado("ya_registrado");
				log.warn("Evento {} {} ignorado: el paciente {} ya estaba registrado", evento.eventId(), tipo, evento.pacienteId());
				yield ResultadoProcesamiento.IGNORADO_YA_REGISTRADO;
			}
		};
	}

}
