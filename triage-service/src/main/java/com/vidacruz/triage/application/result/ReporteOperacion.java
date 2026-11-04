package com.vidacruz.triage.application.result;

import com.vidacruz.triage.domain.model.HoraOperacion;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Reporte de operacion por hora, en la zona horaria configurada. */
public record ReporteOperacion(Instant desde, Instant hasta, ZoneId zonaHoraria, List<HoraOperacion> horas) {
}
