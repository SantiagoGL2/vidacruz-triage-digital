package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.NivelTriaje;
import java.util.EnumMap;
import java.util.Map;

/** Politica con los valores del enunciado (5, 10, 30, 60, 120) o con los minutos indicados. */
public final class PoliticaSlaEstandar implements PoliticaSla {

	private static final Map<NivelTriaje, Integer> POR_DEFECTO = Map.of(
			NivelTriaje.CRITICO, 5,
			NivelTriaje.URGENTE, 10,
			NivelTriaje.URGENTE_2, 30,
			NivelTriaje.NO_URGENTE, 60,
			NivelTriaje.NO_URGENTE_2, 120);

	private final Map<NivelTriaje, Integer> minutos;

	public PoliticaSlaEstandar() {
		this(POR_DEFECTO);
	}

	/** Exige un valor positivo para cada nivel. */
	public PoliticaSlaEstandar(Map<NivelTriaje, Integer> minutos) {
		EnumMap<NivelTriaje, Integer> copia = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			Integer valor = minutos.get(nivel);
			if (valor == null || valor <= 0) {
				throw new IllegalArgumentException("SLA invalido o ausente para " + nivel + ": " + valor);
			}
			copia.put(nivel, valor);
		}
		this.minutos = copia;
	}

	@Override
	public int minutosPara(NivelTriaje nivel) {
		return minutos.get(nivel);
	}

}
