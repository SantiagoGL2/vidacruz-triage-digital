package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * triage.sla.*: SLA en minutos por nivel (RN-02, solo para pacientes NUEVOS: el SLA se congela al registrar) y el
 * proceso de fondo que evalua los vencidos (RF-03).
 */
@ConfigurationProperties("triage.sla")
public record TriageSlaProperties(
		Map<NivelTriaje, Integer> minutos,
		boolean habilitado,
		Duration intervaloEvaluacion,
		int lote) {
}
