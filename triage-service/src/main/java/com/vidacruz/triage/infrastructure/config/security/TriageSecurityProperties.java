package com.vidacruz.triage.infrastructure.config.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** triage.security.*: la API key viene del entorno (TRIAGE_API_KEY), nunca hardcodeada. */
@Validated
@ConfigurationProperties("triage.security")
public record TriageSecurityProperties(@NotBlank String apiKeyHeader, @NotBlank String apiKey) {
}
