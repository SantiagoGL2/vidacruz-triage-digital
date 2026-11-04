package com.vidacruz.assistant.domain.model;

import java.util.List;

/** Un protocolo clínico del corpus oficial: metadatos y una sección por apartado. */
public record Protocolo(String id, String titulo, String version, List<SeccionProtocolo> secciones) {

	public Protocolo {
		secciones = List.copyOf(secciones);
	}

}
