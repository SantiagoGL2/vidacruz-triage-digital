package com.vidacruz.assistant.domain.service;

import com.vidacruz.assistant.domain.exception.ProtocoloInvalidoException;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lee un protocolo escrito en Markdown: un encabezado de metadatos entre lineas {@code ---} (id, titulo, version) y
 * una seccion {@code ## Titulo} por apartado. Cualquier desviacion lanza {@link ProtocoloInvalidoException} indicando
 * el origen: es preferible no indexar nada a indexar un protocolo mal formado.
 */
public class LectorProtocolos {

	private static final Pattern ID_VALIDO = Pattern.compile("PROTO-\\d{3,}");

	public Protocolo leer(String markdown, String origen) {
		String[] lineas = markdown.replace("\r\n", "\n").split("\n", -1);
		int i = 0;
		while (i < lineas.length && lineas[i].isBlank()) {
			i++;
		}
		if (i >= lineas.length || !lineas[i].strip().equals("---")) {
			throw invalido(origen, "falta el encabezado de metadatos (--- / id / titulo / version / ---)");
		}
		Map<String, String> metadatos = new LinkedHashMap<>();
		i++;
		while (i < lineas.length && !lineas[i].strip().equals("---")) {
			String linea = lineas[i].strip();
			int dosPuntos = linea.indexOf(':');
			if (!linea.isEmpty() && dosPuntos > 0) {
				metadatos.put(linea.substring(0, dosPuntos).strip().toLowerCase(), linea.substring(dosPuntos + 1).strip());
			}
			i++;
		}
		if (i >= lineas.length) {
			throw invalido(origen, "el encabezado de metadatos no se cierra con ---");
		}
		i++;
		String id = metadatos.get("id");
		String titulo = metadatos.get("titulo");
		String version = metadatos.get("version");
		if (id == null || !ID_VALIDO.matcher(id).matches()) {
			throw invalido(origen, "id ausente o con formato invalido (esperado PROTO-NNN): " + id);
		}
		if (titulo == null || titulo.isBlank()) {
			throw invalido(origen, "titulo ausente");
		}
		if (version == null || version.isBlank()) {
			throw invalido(origen, "version ausente");
		}

		List<SeccionProtocolo> secciones = new ArrayList<>();
		Set<String> vistas = new HashSet<>();
		String tituloActual = null;
		StringBuilder texto = new StringBuilder();
		for (; i < lineas.length; i++) {
			String linea = lineas[i];
			if (linea.startsWith("## ")) {
				cerrar(secciones, vistas, tituloActual, texto, origen);
				tituloActual = linea.substring(3).strip();
				texto = new StringBuilder();
			}
			else if (tituloActual != null) {
				texto.append(linea).append('\n');
			}
			else if (!linea.isBlank()) {
				throw invalido(origen, "hay texto antes de la primera seccion (## Titulo): " + linea.strip());
			}
		}
		cerrar(secciones, vistas, tituloActual, texto, origen);
		if (secciones.isEmpty()) {
			throw invalido(origen, "no tiene ninguna seccion (## Titulo)");
		}
		return new Protocolo(id, titulo, version, secciones);
	}

	private static void cerrar(List<SeccionProtocolo> secciones, Set<String> vistas, String titulo, StringBuilder texto,
			String origen) {
		if (titulo == null) {
			return;
		}
		String contenido = texto.toString().strip();
		if (titulo.isBlank()) {
			throw invalido(origen, "hay una seccion sin titulo");
		}
		if (contenido.isEmpty()) {
			throw invalido(origen, "la seccion '" + titulo + "' esta vacia");
		}
		if (!vistas.add(titulo.toLowerCase())) {
			throw invalido(origen, "la seccion '" + titulo + "' esta repetida");
		}
		secciones.add(new SeccionProtocolo(titulo, contenido));
	}

	private static ProtocoloInvalidoException invalido(String origen, String motivo) {
		return new ProtocoloInvalidoException("Protocolo invalido (" + origen + "): " + motivo);
	}

}
