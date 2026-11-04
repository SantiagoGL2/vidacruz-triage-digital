package com.vidacruz.triage.application.result;

import com.vidacruz.triage.domain.model.Paciente;
import java.time.Duration;

/** Paciente mas el tiempo en el estado actual, calculado con el Clock del caso de uso. */
public record PacienteConsultado(Paciente paciente, Duration tiempoEnEstadoActual) {
}
