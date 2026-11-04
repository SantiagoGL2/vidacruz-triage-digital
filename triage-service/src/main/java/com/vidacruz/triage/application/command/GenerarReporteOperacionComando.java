package com.vidacruz.triage.application.command;

import java.time.Instant;

/** Rango semiabierto [desde, hasta) del reporte de operacion. */
public record GenerarReporteOperacionComando(Instant desde, Instant hasta) {
}
