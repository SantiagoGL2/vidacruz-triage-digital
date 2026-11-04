package com.vidacruz.triage.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * triage.clasificacion.*: quejas administrativas (nivel 5, RN-01). La lista del enunciado es el valor por defecto
 * en application.yml; una frase vacia haria administrativa cualquier queja, por eso se rechaza al arrancar.
 */
@Validated
@ConfigurationProperties("triage.clasificacion")
public record TriageClasificacionProperties(@NotEmpty List<@NotBlank String> quejasAdministrativas) {
}
