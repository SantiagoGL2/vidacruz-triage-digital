package com.vidacruz.triage.infrastructure.entrypoint.scheduler;

import com.vidacruz.triage.application.context.ContextoCorrelacion;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Proceso de fondo reactivo: ejecuta un caso de uso cada {@code intervalo} con {@code Flux.interval}.
 * Se eligio sobre {@code @Scheduled} porque el caso de uso ya es reactivo (no hace falta un hilo del scheduler que
 * lo suscriba y lo "bloquee") y porque asi se controla el solapamiento: {@code concatMap} garantiza UN ciclo a la vez
 * por instancia (si uno tarda mas que el intervalo, los ticks intermedios se descartan en vez de acumularse).
 * Sobrevive a errores: un ciclo que falla se loguea y el siguiente corre igual.
 * Cada ciclo lleva su propio correlationId (visible en logs y en los eventos que genere).
 */
@Slf4j
public class CicloPeriodico implements SmartLifecycle {

	private final String nombre;
	private final Duration intervalo;
	private final Supplier<Mono<?>> ciclo;
	private volatile Disposable suscripcion;

	public CicloPeriodico(String nombre, Duration intervalo, Supplier<Mono<?>> ciclo) {
		this.nombre = nombre;
		this.intervalo = intervalo;
		this.ciclo = ciclo;
	}

	@Override
	public void start() {
		suscripcion = Flux.interval(intervalo, intervalo)
				.onBackpressureDrop()
				.concatMap(tick -> Mono.defer(ciclo)
						.contextWrite(ctx -> ctx.put(ContextoCorrelacion.CLAVE,
								nombre + "-" + UUID.randomUUID().toString().substring(0, 8)))
						.onErrorResume(e -> {
							log.error("Fallo el ciclo {}: {}", nombre, e.toString(), e);
							return Mono.empty();
						}), 1)
				.subscribe(resultado -> log.debug("Ciclo {}: {}", nombre, resultado),
						e -> log.error("El proceso {} termino inesperadamente", nombre, e));
		log.info("Proceso de fondo {} iniciado (cada {})", nombre, intervalo);
	}

	@Override
	public void stop() {
		Disposable actual = suscripcion;
		if (actual != null) {
			actual.dispose();
			log.info("Proceso de fondo {} detenido", nombre);
		}
	}

	@Override
	public boolean isRunning() {
		Disposable actual = suscripcion;
		return actual != null && !actual.isDisposed();
	}

}
