package com.vidacruz.triage.application.result;

/** Resumen de un ciclo de escalamiento. {@code omitidos}: otra ejecucion se adelanto o el paciente ya no aplicaba. */
public record ResultadoEscalamiento(int candidatos, int escalados, int omitidos, int fallidos) {
}
