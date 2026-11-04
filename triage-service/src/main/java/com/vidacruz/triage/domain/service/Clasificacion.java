package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriaje;
import java.util.List;

/** Resultado de clasificar: el nivel y los motivos (condiciones cumplidas) para auditoria y logs. */
public record Clasificacion(NivelTriaje nivel, List<String> motivos) {

	public Clasificacion {
		motivos = List.copyOf(motivos);
	}

	public String detalle() {
		return "Nivel %d %s: %s".formatted(nivel.numero(), nivel, String.join(", ", motivos));
	}

}
