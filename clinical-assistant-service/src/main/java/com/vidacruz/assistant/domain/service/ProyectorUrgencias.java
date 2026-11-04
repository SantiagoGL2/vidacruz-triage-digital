package com.vidacruz.assistant.domain.service;

import com.vidacruz.assistant.domain.event.EventoClinico;
import com.vidacruz.assistant.domain.model.OperacionEstado;

/** Traduce un evento de triage a la operacion que debe aplicarse sobre la vista de urgencias. */
public class ProyectorUrgencias {

	public OperacionEstado operacionPara(EventoClinico evento) {
		return switch (evento.tipo()) {
			case PACIENTE_REGISTRADO -> new OperacionEstado.Registrar(evento.pacienteId(), evento.nivel(), evento.fechaRegistro());
			case PACIENTE_EN_CONSULTA -> new OperacionEstado.PasarAConsulta(evento.pacienteId());
			case PACIENTE_ESCALADO -> new OperacionEstado.Escalar(evento.pacienteId());
			case PACIENTE_ALTA -> new OperacionEstado.DarDeAlta(evento.pacienteId());
		};
	}

}
