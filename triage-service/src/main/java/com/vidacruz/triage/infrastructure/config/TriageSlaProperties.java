package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * triage.sla.*: SLA en minutos por nivel (RN-02, solo para pacientes NUEVOS: el SLA se congela al registrar) y el
 * proceso de fondo que evalua los vencidos (RF-03). Los minutos que no se configuran (propiedad ausente o vacia) toman el
 * SLA por defecto del nivel ({@link NivelTriaje#slaPorDefectoMinutos()}): el enum es la unica fuente de verdad.
 */
@ConfigurationProperties("triage.sla")
public record TriageSlaProperties(
		Map<NivelTriaje, Integer> minutos,
		boolean habilitado,
		Duration intervaloEvaluacion,
		int lote) {

	public TriageSlaProperties {
		Map<NivelTriaje, Integer> efectivos = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			Integer configurado = minutos == null ? null : minutos.get(nivel);
			efectivos.put(nivel, configurado != null ? configurado : nivel.slaPorDefectoMinutos());
		}
		minutos = efectivos;
	}

}
