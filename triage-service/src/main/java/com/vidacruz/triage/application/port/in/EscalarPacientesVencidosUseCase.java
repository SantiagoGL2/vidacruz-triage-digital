package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.result.ResultadoEscalamiento;
import reactor.core.publisher.Mono;

public interface EscalarPacientesVencidosUseCase {

	/** Un ciclo de RF-03: escala a los pacientes REGISTRADO cuyo SLA vencio. Idempotente (RN-03). */
	Mono<ResultadoEscalamiento> escalarVencidos();

}
