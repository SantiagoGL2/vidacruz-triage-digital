package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import java.util.EnumMap;
import java.util.Map;

/** Politica con el SLA por defecto de cada {@link NivelTriaje} (enunciado: 5, 10, 30, 60, 120) o con los minutos indicados. */
public final class PoliticaSlaEstandar implements PoliticaSla {

	private final Map<NivelTriaje, Integer> minutos;

	public PoliticaSlaEstandar() {
		this(porDefecto());
	}

	/** Los SLA por defecto que declara cada nivel. */
	public static Map<NivelTriaje, Integer> porDefecto() {
		EnumMap<NivelTriaje, Integer> minutos = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			minutos.put(nivel, nivel.slaPorDefectoMinutos());
		}
		return minutos;
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
