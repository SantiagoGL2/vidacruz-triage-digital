package com.vidacruz.assistant.domain.model;

/**
 * Unidad que se indexa y se recupera: UNA sección de UN protocolo (no un trozo de N caracteres).
 * {@code id} es determinista (protocolo + seccion) y {@code hash} cambia si cambia el contenido o la version, de modo
 * que reindexar solo toca lo que cambio. {@code texto} incluye el titulo del protocolo y el de la seccion para mejorar
 * la recuperacion.
 */
public record FragmentoProtocolo(String id, String protocolo, String titulo, String seccion, String version,
		String texto, String hash) {
}
