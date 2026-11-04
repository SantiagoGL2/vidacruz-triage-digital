package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.exception.CampoInvalido;
import com.vidacruz.triage.domain.exception.DatoClinicoInvalidoException;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.DatosRegistro;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.SignosVitales;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Servicio de dominio del RF-01: valida (datos personales y signos vitales a la vez), clasifica (RN-01), asigna
 * SLA (RN-02) y registra al paciente. Es la unica puerta de entrada de un registro: los casos de uso no validan.
 */
public final class RegistradorPacientes {

	private final ClasificadorTriaje clasificador;
	private final PoliticaSla politicaSla;

	public RegistradorPacientes(ClasificadorTriaje clasificador, PoliticaSla politicaSla) {
		this.clasificador = clasificador;
		this.politicaSla = politicaSla;
	}

	/**
	 * @throws DatoClinicoInvalidoException con TODOS los campos invalidos (personales y signos vitales juntos)
	 */
	public CambioClinico registrar(UUID id, DatosRegistro datos, Instant ahora) {
		List<CampoInvalido> errores = new ArrayList<>(
				Paciente.validarDatosPersonales(datos.nombreCompleto(), datos.edad(), datos.quejaPrincipal()));
		errores.addAll(SignosVitales.validar(datos.frecuenciaCardiaca(), datos.presionSistolica(),
				datos.presionDiastolica(), datos.spo2(), datos.temperatura(), datos.dolor(), datos.gcs()));
		if (!errores.isEmpty()) {
			throw new DatoClinicoInvalidoException(errores);
		}
		SignosVitales signos = new SignosVitales(datos.frecuenciaCardiaca(), datos.presionSistolica(),
				datos.presionDiastolica(), datos.spo2(), datos.temperatura(), datos.dolor(), datos.gcs());
		Clasificacion clasificacion = clasificador.evaluar(signos, datos.quejaPrincipal());
		int sla = politicaSla.minutosPara(clasificacion.nivel());
		return Paciente.registrar(id, datos.nombreCompleto(), datos.edad(), signos, datos.quejaPrincipal(),
				clasificacion.nivel(), sla, ahora, clasificacion.detalle());
	}

}
