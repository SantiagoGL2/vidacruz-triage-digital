package com.vidacruz.triage.application.result;

/** Resumen de un ciclo del publicador: {@code pendientes} = lo que sigue sin publicar tras el ciclo. */
public record ResultadoPublicacion(int publicados, int fallidos, long pendientes) {
}
