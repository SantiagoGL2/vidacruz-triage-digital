package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.command.RegistrarPacienteComando;
import com.vidacruz.triage.domain.model.Paciente;
import reactor.core.publisher.Mono;

public interface RegistrarPacienteUseCase {

	/** Valida, clasifica, persiste, audita y deja el evento en el outbox, todo en una transaccion. */
	Mono<Paciente> registrar(RegistrarPacienteComando comando);

}
