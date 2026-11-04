package com.vidacruz.assistant.domain.service;

import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Fragmentacion por seccion: cada seccion de cada protocolo es un fragmento (no se corta por numero de caracteres, asi
 * una respuesta nunca mezcla ni parte un apartado y la cita «protocolo + seccion» es exacta). El texto del fragmento
 * lleva el id y el titulo del protocolo y el nombre de la seccion, que mejora la recuperacion (la pregunta suele nombrar
 * el tema aunque la seccion no lo repita).
 */
public class FragmentadorProtocolos {

	public List<FragmentoProtocolo> fragmentar(Protocolo protocolo) {
		List<FragmentoProtocolo> fragmentos = new ArrayList<>();
		for (SeccionProtocolo seccion : protocolo.secciones()) {
			String texto = "%s — %s\nSección: %s\n%s".formatted(protocolo.id(), protocolo.titulo(), seccion.titulo(),
					seccion.texto());
			fragmentos.add(new FragmentoProtocolo(
					idDe(protocolo.id(), seccion.titulo()), protocolo.id(), protocolo.titulo(), seccion.titulo(),
					protocolo.version(), texto, hash(texto + "\n" + protocolo.version())));
		}
		return fragmentos;
	}

	public List<FragmentoProtocolo> fragmentar(List<Protocolo> protocolos) {
		return protocolos.stream().flatMap(p -> fragmentar(p).stream()).toList();
	}

	/** Id determinista: el mismo protocolo + seccion da siempre el mismo id (reindexar sobrescribe, no duplica). */
	public static String idDe(String protocolo, String seccion) {
		return protocolo.toLowerCase() + ":" + Textos.slug(seccion);
	}

	private static String hash(String contenido) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
