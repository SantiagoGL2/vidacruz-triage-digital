package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.result.PacienteConsultado;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface ConsultarPacienteUseCase {

	Mono<PacienteConsultado> consultar(UUID id);

}
