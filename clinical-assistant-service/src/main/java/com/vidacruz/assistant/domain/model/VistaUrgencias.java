package com.vidacruz.assistant.domain.model;

import com.vidacruz.assistant.domain.model.OperacionEstado.DarDeAlta;
import com.vidacruz.assistant.domain.model.OperacionEstado.Escalar;
import com.vidacruz.assistant.domain.model.OperacionEstado.PasarAConsulta;
import com.vidacruz.assistant.domain.model.OperacionEstado.Registrar;
import com.vidacruz.assistant.domain.model.enums.EstadoPaciente;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reglas de la proyeccion de urgencias, en Java puro e inmutables. Es la <b>especificacion ejecutable</b> de lo que
 * el adapter de Redis hace de forma atomica en un script Lua: los tests unitarios fijan las reglas aqui y un test de
 * integracion comprueba que Redis llega al mismo resultado con la misma secuencia de eventos.
 * <p>
 * Los contadores se derivan de los pacientes activos, asi que por construccion no pueden quedar negativos.
 */
public final class VistaUrgencias {

	private final Map<UUID, PacienteActivo> pacientes;
	private final Set<UUID> eventosAplicados;
	private final Instant ultimoEventoEn;

	private VistaUrgencias(Map<UUID, PacienteActivo> pacientes, Set<UUID> eventosAplicados, Instant ultimoEventoEn) {
		this.pacientes = pacientes;
		this.eventosAplicados = eventosAplicados;
		this.ultimoEventoEn = ultimoEventoEn;
	}

	public static VistaUrgencias vacia() {
		return new VistaUrgencias(Map.of(), Set.of(), null);
	}

	/** Resultado de {@link #aplicar}: la vista nueva (esta no cambia) y que paso. */
	public record Aplicacion(VistaUrgencias vista, ResultadoAplicacion resultado) {
	}

	public Aplicacion aplicar(UUID eventId, Instant ocurridoEn, OperacionEstado operacion) {
		if (eventosAplicados.contains(eventId)) {
			return new Aplicacion(this, ResultadoAplicacion.DUPLICADO);
		}
		Map<UUID, PacienteActivo> nuevos = new HashMap<>(pacientes);
		ResultadoAplicacion resultado = ejecutar(nuevos, operacion);
		Set<UUID> vistos = new HashSet<>(eventosAplicados);
		vistos.add(eventId);   // tambien los ignorados: reentregarlos no cambia nada
		// el ultimo evento es el mas reciente por occurredAt (entre particiones el orden de llegada no es el de ocurrencia)
		Instant ultimo = ultimoEventoEn == null || ocurridoEn.isAfter(ultimoEventoEn) ? ocurridoEn : ultimoEventoEn;
		return new Aplicacion(new VistaUrgencias(Map.copyOf(nuevos), Set.copyOf(vistos), ultimo), resultado);
	}

	private static ResultadoAplicacion ejecutar(Map<UUID, PacienteActivo> pacientes, OperacionEstado operacion) {
		PacienteActivo actual = pacientes.get(operacion.pacienteId());
		return switch (operacion) {
			case Registrar r -> {
				if (actual != null) {
					yield ResultadoAplicacion.IGNORADO_YA_REGISTRADO;
				}
				pacientes.put(r.pacienteId(),
						new PacienteActivo(r.pacienteId(), r.nivel(), EstadoPaciente.REGISTRADO, false, r.fechaRegistro()));
				yield ResultadoAplicacion.APLICADO;
			}
			case PasarAConsulta c -> {
				if (actual == null) {
					yield ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO;
				}
				pacientes.put(c.pacienteId(), actual.conEstado(EstadoPaciente.EN_CONSULTA));
				yield ResultadoAplicacion.APLICADO;
			}
			case Escalar e -> {
				if (actual == null) {
					yield ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO;
				}
				if (actual.escalado()) {
					yield ResultadoAplicacion.SIN_CAMBIO;
				}
				pacientes.put(e.pacienteId(), actual.escalar());
				yield ResultadoAplicacion.APLICADO;
			}
			case DarDeAlta a -> {
				if (actual == null) {
					yield ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO;
				}
				pacientes.remove(a.pacienteId());
				yield ResultadoAplicacion.APLICADO;
			}
		};
	}

	public EstadoUrgencias estado() {
		Map<NivelTriaje, Long> porNivel = new EnumMap<>(NivelTriaje.class);
		long escalados = 0;
		for (PacienteActivo p : pacientes.values()) {
			porNivel.merge(p.nivel(), 1L, Long::sum);
			if (p.escalado()) {
				escalados++;
			}
		}
		return new EstadoUrgencias(porNivel, escalados, ultimoEventoEn);
	}

	public Map<UUID, PacienteActivo> pacientes() {
		return pacientes;
	}

}
