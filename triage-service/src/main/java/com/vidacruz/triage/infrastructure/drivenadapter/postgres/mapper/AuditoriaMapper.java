package com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper;

import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.entity.AuditoriaEntity;
import java.util.UUID;

/** Cambio clinico -> fila de auditoria. Solo en una direccion: la auditoria se escribe, no se rehidrata. */
public final class AuditoriaMapper {

	private AuditoriaMapper() {
	}

	public static AuditoriaEntity aEntidad(CambioClinico cambio) {
		return AuditoriaEntity.builder()
				.id(UUID.randomUUID())
				.pacienteId(cambio.paciente().id())
				.fechaEvento(cambio.ocurridoEn())
				.estadoAnterior(cambio.estadoAnterior() == null ? null : cambio.estadoAnterior().name())
				.estadoNuevo(cambio.estadoNuevo().name())
				.tipoEvento(cambio.tipoEvento().name())
				.detalle(cambio.detalle())
				.build();
	}

}
