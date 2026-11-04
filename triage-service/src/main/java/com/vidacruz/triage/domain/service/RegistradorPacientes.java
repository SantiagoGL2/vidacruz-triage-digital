package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Servicio de dominio del RF-01: valida, clasifica (RN-01), asigna SLA (RN-02) y registra al paciente. */
public final class RegistradorPacientes {

	private final ClasificadorTriaje clasificador;
	private final PoliticaSla politicaSla;

	public RegistradorPacientes(ClasificadorTriaje clasificador, PoliticaSla politicaSla) {
		this.clasificador = clasificador;
		this.politicaSla = politicaSla;
	}

	public CambioClinico registrar(UUID id, String nombreCompleto, int edad, SignosVitales signos,
			String quejaPrincipal, Instant ahora) {
		List<CampoInvalido> errores = Paciente.validarDatosPersonales(nombreCompleto, edad, quejaPrincipal);
		if (!errores.isEmpty()) {
			throw new DatoClinicoInvalidoException(errores);
		}
		Clasificacion clasificacion = clasificador.evaluar(signos, quejaPrincipal);
		int sla = politicaSla.minutosPara(clasificacion.nivel());
		return Paciente.registrar(id, nombreCompleto, edad, signos, quejaPrincipal, clasificacion.nivel(), sla, ahora,
				clasificacion.detalle());
	}

}
