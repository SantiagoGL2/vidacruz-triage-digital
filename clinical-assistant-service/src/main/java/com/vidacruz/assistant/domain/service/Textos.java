package com.vidacruz.assistant.domain.service;

import java.text.Normalizer;
import java.util.Locale;

/** Normalizacion de texto para comparar nombres (protocolos, secciones) sin depender de tildes ni mayusculas. */
public final class Textos {

	private Textos() {
	}

	/** Minusculas, sin tildes, espacios colapsados. */
	public static String normalizar(String texto) {
		if (texto == null) {
			return "";
		}
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	/** Identificador seguro para claves: {@code "Medidas iniciales"} -> {@code "medidas-iniciales"}. */
	public static String slug(String texto) {
		return normalizar(texto).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
	}

}
