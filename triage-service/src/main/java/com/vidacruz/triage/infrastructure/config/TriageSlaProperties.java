package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** triage.sla.minutos.{NIVEL}: SLA por nivel (RN-02). */
@ConfigurationProperties("triage.sla")
public record TriageSlaProperties(Map<NivelTriaje, Integer> minutos) {
}
