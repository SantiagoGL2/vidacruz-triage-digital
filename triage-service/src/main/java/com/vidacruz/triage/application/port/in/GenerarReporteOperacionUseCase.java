package com.vidacruz.triage.application.port.in;

import com.vidacruz.triage.application.command.GenerarReporteOperacionComando;
import com.vidacruz.triage.application.result.ReporteOperacion;
import reactor.core.publisher.Mono;

public interface GenerarReporteOperacionUseCase {

	Mono<ReporteOperacion> generar(GenerarReporteOperacionComando comando);

}
