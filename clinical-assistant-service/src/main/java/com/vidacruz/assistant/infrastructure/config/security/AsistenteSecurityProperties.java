package com.vidacruz.assistant.infrastructure.config.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** assistant.security.*: la API key viene del entorno (ASSISTANT_API_KEY), nunca hardcodeada. */
@Validated
@ConfigurationProperties("assistant.security")
public record AsistenteSecurityProperties(@NotBlank String apiKeyHeader, @NotBlank String apiKey) {
}
