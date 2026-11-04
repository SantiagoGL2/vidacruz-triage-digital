package com.vidacruz.triage.infrastructure.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** triage.reportes.*: zona horaria en la que se agrupa por hora (RF-05) y rango maximo consultable. */
@ConfigurationProperties("triage.reportes")
public record TriageReportesProperties(ZoneId zonaHoraria, int rangoMaximoDias) {
}
