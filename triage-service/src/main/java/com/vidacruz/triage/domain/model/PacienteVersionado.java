package com.vidacruz.triage.domain.model;

/**
 * Paciente leido junto con la version de la fila. La version es un concepto de concurrencia que el puerto de
 * repositorio debe transportar para el optimistic locking: se lee aqui y se devuelve en {@code actualizar}.
 */
public record PacienteVersionado(Paciente paciente, long version) {
}
