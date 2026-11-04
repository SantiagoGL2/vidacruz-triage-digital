package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface CambiarEstadoPacienteUseCase {

	Mono<Paciente> cambiarEstado(UUID id, EstadoPaciente destino);

}
