package com.vidacruz.triage.domain.model;

import java.time.Duration;

/**
 * Reintentos del publicador del outbox: tras el fallo n el evento espera {@code backoffInicial * 2^(n-1)} (con tope
 * {@code backoffMaximo}) y, al llegar a {@code maxIntentos}, deja de reintentarse (queda para revision manual).
 */
public record PoliticaReintentoOutbox(int maxIntentos, Duration backoffInicial, Duration backoffMaximo) {
}
