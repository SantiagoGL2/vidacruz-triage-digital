package com.vidacruz.assistant.domain.model;

/** Resultado de aplicar un evento y la vista tal como quedo (para refrescar metricas sin otra lectura). */
public record AplicacionEvento(ResultadoAplicacion resultado, EstadoUrgencias vista) {
}
