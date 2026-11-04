package com.vidacruz.assistant.domain.model.enums;

import java.util.Optional;

/** Tipos de evento de {@code triaje.eventos} que este servicio sabe proyectar. Cualquier otro se ignora. */
public enum TipoEventoClinico {

	PACIENTE_REGISTRADO("paciente_registrado"),
	PACIENTE_EN_CONSULTA("paciente_en_consulta"),
	PACIENTE_ESCALADO("paciente_escalado"),
	PACIENTE_ALTA("paciente_alta");

	private final String codigo;

	TipoEventoClinico(String codigo) {
		this.codigo = codigo;
	}

	/** Valor de {@code eventType} en el envelope. */
	public String codigo() {
		return codigo;
	}

	/** Vacio si el tipo no se conoce: compatibilidad hacia adelante con eventos nuevos de triage. */
	public static Optional<TipoEventoClinico> desdeCodigo(String codigo) {
		for (TipoEventoClinico tipo : values()) {
			if (tipo.codigo.equals(codigo)) {
				return Optional.of(tipo);
			}
		}
		return Optional.empty();
	}

}
