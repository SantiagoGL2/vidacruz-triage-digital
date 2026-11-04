package com.vidacruz.assistant.domain.model;

import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Vista de urgencias en un instante: pacientes activos (los que no estan en ALTA) por nivel, cuantos de ellos estan
 * escalados y el instante del ultimo evento aplicado (indica que tan fresca es la vista: consistencia eventual).
 * {@code porNivel} siempre trae los 5 niveles; {@code ultimoEventoEn} es null si aun no se aplico ningun evento.
 */
public record EstadoUrgencias(Map<NivelTriaje, Long> porNivel, long escalados, Instant ultimoEventoEn) {

	public EstadoUrgencias {
		EnumMap<NivelTriaje, Long> completo = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			// un contador nunca es negativo, pase lo que pase en el almacen
			completo.put(nivel, Math.max(0L, porNivel.getOrDefault(nivel, 0L)));
		}
		porNivel = Collections.unmodifiableMap(completo);
		escalados = Math.max(0L, escalados);
	}

	public static EstadoUrgencias vacio() {
		return new EstadoUrgencias(Map.of(), 0, null);
	}

	public long totalActivos() {
		return porNivel.values().stream().mapToLong(Long::longValue).sum();
	}

}
