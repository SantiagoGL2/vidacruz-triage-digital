package com.vidacruz.assistant.application.result;

import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import java.time.Instant;

/** Vista de urgencias y el instante en que se consulto (para que el cliente sepa cuan vieja es respecto a ahora). */
public record EstadoUrgenciasConsultado(EstadoUrgencias estado, Instant consultadoEn) {
}
