package com.vidacruz.triage.domain.model;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Una hora del reporte de operacion (RF-05). {@code porNivel} siempre trae los 5 niveles; el promedio y el p95 de
 * espera son {@code null} cuando nadie fue atendido entre los registrados esa hora.
 */
public record HoraOperacion(
		ZonedDateTime hora,
		long totalRegistrados,
		Map<NivelTriaje, Long> porNivel,
		long pacientesAtendidos,
		Double esperaPromedioMinutos,
		Double esperaP95Minutos) {

	public HoraOperacion {
		EnumMap<NivelTriaje, Long> completo = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			completo.put(nivel, porNivel.getOrDefault(nivel, 0L));
		}
		porNivel = Collections.unmodifiableMap(completo);
	}

}
