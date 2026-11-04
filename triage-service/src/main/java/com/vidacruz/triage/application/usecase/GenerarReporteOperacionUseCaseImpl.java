package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.command.GenerarReporteOperacionComando;
import com.vidacruz.triage.application.exception.ParametroReporteInvalidoException;
import com.vidacruz.triage.application.port.in.GenerarReporteOperacionUseCase;
import com.vidacruz.triage.application.result.ReporteOperacion;
import com.vidacruz.triage.domain.port.out.ReporteOperacionPort;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import reactor.core.publisher.Mono;

/**
 * RF-05. Solo valida el rango y delega: el agregado (conteos, promedio y p95 por hora) lo calcula PostgreSQL en una
 * unica consulta; aqui no se itera sobre registros.
 */
public class GenerarReporteOperacionUseCaseImpl implements GenerarReporteOperacionUseCase {

	private final ReporteOperacionPort reporte;
	private final ZoneId zona;
	private final int rangoMaximoDias;

	public GenerarReporteOperacionUseCaseImpl(ReporteOperacionPort reporte, ZoneId zona, int rangoMaximoDias) {
		this.reporte = reporte;
		this.zona = zona;
		this.rangoMaximoDias = rangoMaximoDias;
	}

	@Override
	public Mono<ReporteOperacion> generar(GenerarReporteOperacionComando comando) {
		return Mono.defer(() -> {
			validar(comando.desde(), comando.hasta());
			return reporte.reportePorHora(comando.desde(), comando.hasta(), zona).collectList()
					.map(horas -> new ReporteOperacion(comando.desde(), comando.hasta(), zona, horas));
		});
	}

	private void validar(Instant desde, Instant hasta) {
		if (desde == null) {
			throw new ParametroReporteInvalidoException("desde", null, "parametro obligatorio");
		}
		if (hasta == null) {
			throw new ParametroReporteInvalidoException("hasta", null, "parametro obligatorio");
		}
		if (!desde.isBefore(hasta)) {
			throw new ParametroReporteInvalidoException("desde", desde, "debe ser anterior a 'hasta' (rango [desde, hasta))");
		}
		if (Duration.between(desde, hasta).compareTo(Duration.ofDays(rangoMaximoDias)) > 0) {
			throw new ParametroReporteInvalidoException("hasta", hasta,
					"el rango no puede superar " + rangoMaximoDias + " dias");
		}
	}

}
