package com.vidacruz.triage.infrastructure.drivenadapter.memory.adapter;

import com.vidacruz.triage.domain.port.out.DeduplicacionEventosPort;
import com.vidacruz.triage.infrastructure.config.TriageAlertasProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Idempotencia en memoria con cache acotada (LRU de {@code capacidad-deduplicacion} eventId). Se eligio sobre una
 * tabla de Postgres porque la alerta es una notificacion efimera: un duplicado tras reiniciar o tras un rebalanceo
 * hacia otra replica es inofensivo, y evita una escritura y una migracion por cada evento. Ver ADR 0002.
 */
@Component
class DeduplicacionEventosMemoriaAdapter implements DeduplicacionEventosPort {

	private final Map<UUID, Boolean> vistos;

	DeduplicacionEventosMemoriaAdapter(TriageAlertasProperties propiedades) {
		int capacidad = propiedades.consumidor().capacidadDeduplicacion();
		this.vistos = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
				return size() > capacidad;
			}
		});
	}

	@Override
	public Mono<Boolean> registrarSiNuevo(UUID eventId) {
		return Mono.fromSupplier(() -> vistos.putIfAbsent(eventId, Boolean.TRUE) == null);
	}

}
