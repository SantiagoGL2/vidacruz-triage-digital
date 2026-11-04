package com.vidacruz.triage.domain.service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lista de quejas administrativas (nivel 5). SUPUESTO (docs/supuestos.md): la queja se normaliza (minusculas,
 * sin tildes, espacios colapsados, trim) y es administrativa si CONTIENE alguna frase normalizada de la lista.
 */
public final class QuejasAdministrativas {

	/** Lista del enunciado. */
	public static final List<String> POR_DEFECTO = List.of(
			"documentos", "recolección de resultados", "renovación de recetario", "vacunación", "seguimiento");

	private final List<String> frases;

	public QuejasAdministrativas() {
		this(POR_DEFECTO);
	}

	public QuejasAdministrativas(Collection<String> frases) {
		this.frases = frases.stream().map(QuejasAdministrativas::normalizar).toList();
		if (this.frases.stream().anyMatch(String::isEmpty)) {
			// una frase vacia haria que TODA queja fuera administrativa
			throw new IllegalArgumentException("Las frases administrativas no pueden estar vacias");
		}
	}

	/** Frase administrativa (normalizada) que coincide con la queja, si existe. */
	public Optional<String> coincidencia(String quejaPrincipal) {
		String queja = normalizar(quejaPrincipal);
		return frases.stream().filter(queja::contains).findFirst();
	}

	public boolean esAdministrativa(String quejaPrincipal) {
		return coincidencia(quejaPrincipal).isPresent();
	}

	static String normalizar(String texto) {
		if (texto == null) {
			return "";
		}
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

}
