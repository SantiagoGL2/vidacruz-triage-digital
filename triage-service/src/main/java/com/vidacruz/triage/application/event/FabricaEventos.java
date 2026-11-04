package com.vidacruz.triage.application.event;

import com.vidacruz.triage.domain.event.EventoTriaje;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.Paciente;
import java.util.UUID;

/** Construye el envelope a partir de un cambio clinico del dominio. */
public final class FabricaEventos {

	private final int version;

	public FabricaEventos(int version) {
		this.version = version;
	}

	public EventoTriaje desde(CambioClinico cambio, String correlationId) {
		Paciente p = cambio.paciente();
		var payload = new EventoTriaje.Payload(
				p.id(),
				p.nivel().name(),
				p.nivel().numero(),
				cambio.estadoAnterior() == null ? null : cambio.estadoAnterior().name(),
				cambio.estadoNuevo().name(),
				p.fechaRegistro(),
				p.slaMinutos(),
				p.escalado(),
				p.fechaPrimeraConsulta(),
				p.fechaUltimoCambioEstado());
		return new EventoTriaje(UUID.randomUUID(), cambio.tipoEvento().nombreEvento(), cambio.ocurridoEn(), version,
				correlationId, payload);
	}

}
