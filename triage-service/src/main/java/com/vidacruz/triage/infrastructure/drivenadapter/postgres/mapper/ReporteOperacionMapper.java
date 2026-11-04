package com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper;

import com.vidacruz.triage.domain.model.HoraOperacion;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Fila de reporte-operacion.sql -> {@link HoraOperacion}. Solo mapea: no calcula nada. */
public final class ReporteOperacionMapper {

	private ReporteOperacionMapper() {
	}

	public static HoraOperacion aDominio(Readable fila, ZoneId zona) {
		Map<NivelTriaje, Long> porNivel = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			porNivel.put(nivel, fila.get("nivel_" + nivel.numero(), Long.class));
		}
		return new HoraOperacion(
				fila.get("hora", OffsetDateTime.class).atZoneSameInstant(zona),
				fila.get("total_registrados", Long.class),
				porNivel,
				fila.get("pacientes_atendidos", Long.class),
				fila.get("espera_promedio_minutos", Double.class),
				fila.get("espera_p95_minutos", Double.class));
	}

}
