package com.vidacruz.assistant.domain.event;

import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.domain.model.enums.TipoEventoClinico;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Evento de triage ya validado. Se construye siempre con {@link #crear}, que recibe los datos crudos del mensaje
 * (strings) y valida todo junto: un evento a medias nunca llega a la proyeccion.
 * {@code nivel} y {@code fechaRegistro} solo se exigen en {@code paciente_registrado}; en las demas transiciones la
 * vista usa el nivel que ya guarda del paciente (asi un evento inconsistente no puede descuadrar los contadores).
 */
public record EventoClinico(
		UUID eventId,
		TipoEventoClinico tipo,
		Instant ocurridoEn,
		String correlationId,
		UUID pacienteId,
		NivelTriaje nivel,
		Instant fechaRegistro) {

	public static EventoClinico crear(UUID eventId, TipoEventoClinico tipo, Instant ocurridoEn, String correlationId,
			String pacienteId, String nivel, String fechaRegistro) {
		List<String> errores = new ArrayList<>();
		if (eventId == null) {
			errores.add("eventId obligatorio");
		}
		if (ocurridoEn == null) {
			errores.add("occurredAt obligatorio");
		}
		UUID paciente = null;
		if (pacienteId == null || pacienteId.isBlank()) {
			errores.add("payload.pacienteId obligatorio");
		}
		else {
			try {
				paciente = UUID.fromString(pacienteId);
			}
			catch (IllegalArgumentException e) {
				errores.add("payload.pacienteId no es un UUID: " + pacienteId);
			}
		}
		NivelTriaje nivelTriaje = null;
		Instant registro = null;
		if (tipo == TipoEventoClinico.PACIENTE_REGISTRADO) {
			if (nivel == null || nivel.isBlank()) {
				errores.add("payload.nivel obligatorio en paciente_registrado");
			}
			else {
				try {
					nivelTriaje = NivelTriaje.valueOf(nivel);
				}
				catch (IllegalArgumentException e) {
					errores.add("payload.nivel desconocido: " + nivel);
				}
			}
			if (fechaRegistro == null || fechaRegistro.isBlank()) {
				errores.add("payload.fechaRegistro obligatorio en paciente_registrado");
			}
			else {
				try {
					registro = Instant.parse(fechaRegistro);
				}
				catch (DateTimeParseException e) {
					errores.add("payload.fechaRegistro no es una fecha ISO 8601: " + fechaRegistro);
				}
			}
		}
		if (!errores.isEmpty()) {
			throw new EventoInvalidoException("Evento " + tipo.codigo() + " invalido: " + String.join("; ", errores));
		}
		return new EventoClinico(eventId, tipo, ocurridoEn, correlationId, paciente, nivelTriaje, registro);
	}

}
