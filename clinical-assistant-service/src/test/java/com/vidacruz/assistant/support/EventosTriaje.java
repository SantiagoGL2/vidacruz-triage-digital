package com.vidacruz.assistant.support;

import java.time.Instant;
import java.util.UUID;

/**
 * Construye eventos con el formato documentado en docs/eventos.md (envelope + payload), tal como los publica el
 * outbox de triage-service.
 */
public final class EventosTriaje {

	private EventosTriaje() {
	}

	public record Evento(UUID eventId, UUID pacienteId, String json) {
	}

	public static Evento registrado(UUID pacienteId, String nivel) {
		return evento("paciente_registrado", pacienteId, nivel, null, "REGISTRADO", false);
	}

	public static Evento enConsulta(UUID pacienteId, String nivel) {
		return evento("paciente_en_consulta", pacienteId, nivel, "REGISTRADO", "EN_CONSULTA", false);
	}

	public static Evento escalado(UUID pacienteId, String nivel) {
		return evento("paciente_escalado", pacienteId, nivel, "REGISTRADO", "REGISTRADO", true);
	}

	public static Evento alta(UUID pacienteId, String nivel) {
		return evento("paciente_alta", pacienteId, nivel, "EN_CONSULTA", "ALTA", false);
	}

	public static Evento evento(String tipo, UUID pacienteId, String nivel, String estadoAnterior, String estadoNuevo,
			boolean escalado) {
		UUID eventId = UUID.randomUUID();
		String ahora = Instant.now().toString();
		String json = """
				{
				  "eventId": "%s",
				  "eventType": "%s",
				  "occurredAt": "%s",
				  "version": 1,
				  "correlationId": "test-%s",
				  "payload": {
				    "pacienteId": "%s",
				    "nivel": "%s",
				    "nivelNumero": 1,
				    "estadoAnterior": %s,
				    "estadoNuevo": "%s",
				    "fechaRegistro": "%s",
				    "slaMinutos": 5,
				    "escalado": %s,
				    "fechaPrimeraConsulta": null,
				    "fechaCambioEstado": "%s"
				  }
				}
				""".formatted(eventId, tipo, ahora, eventId.toString().substring(0, 8), pacienteId, nivel,
				estadoAnterior == null ? "null" : "\"" + estadoAnterior + "\"", estadoNuevo, ahora, escalado, ahora);
		return new Evento(eventId, pacienteId, json);
	}

}
