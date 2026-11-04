package com.vidacruz.triage.domain.port.out;

import com.vidacruz.triage.domain.model.HoraOperacion;
import java.time.Instant;
import java.time.ZoneId;
import reactor.core.publisher.Flux;

/** Reporte de operacion (RF-05): el agregado lo calcula la base de datos, una fila por hora del rango [desde, hasta). */
public interface ReporteOperacionPort {

	Flux<HoraOperacion> reportePorHora(Instant desde, Instant hasta, ZoneId zona);

}
