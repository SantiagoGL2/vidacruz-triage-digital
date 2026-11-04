package com.vidacruz.assistant.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vidacruz.assistant.domain.exception.ProtocoloInvalidoException;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LectorProtocolosTests {

	private final LectorProtocolos lector = new LectorProtocolos();

	private static final String VALIDO = """
			---
			id: PROTO-009
			titulo: Protocolo de prueba
			version: 2.1
			---

			## Primera
			Texto uno.
			con dos lineas

			## Segunda
			Texto dos.
			""";

	@Test
	void leeMetadatosYUnaSeccionPorApartado() {
		Protocolo p = lector.leer(VALIDO, "prueba.md");

		assertThat(p.id()).isEqualTo("PROTO-009");
		assertThat(p.titulo()).isEqualTo("Protocolo de prueba");
		assertThat(p.version()).isEqualTo("2.1");
		assertThat(p.secciones()).extracting(SeccionProtocolo::titulo).containsExactly("Primera", "Segunda");
		assertThat(p.secciones().get(0).texto()).isEqualTo("Texto uno.\ncon dos lineas");
	}

	@Test
	void rechazaProtocolosMalFormadosIndicandoElOrigen() {
		assertThatThrownBy(() -> lector.leer("## Solo seccion\ntexto", "a.md"))
				.isInstanceOf(ProtocoloInvalidoException.class).hasMessageContaining("a.md").hasMessageContaining("encabezado");
		assertThatThrownBy(() -> lector.leer(VALIDO.replace("PROTO-009", "XYZ-1"), "b.md"))
				.hasMessageContaining("id ausente o con formato invalido");
		assertThatThrownBy(() -> lector.leer(VALIDO.replace("titulo: Protocolo de prueba\n", ""), "c.md"))
				.hasMessageContaining("titulo ausente");
		assertThatThrownBy(() -> lector.leer(VALIDO.replace("version: 2.1\n", ""), "d.md"))
				.hasMessageContaining("version ausente");
		assertThatThrownBy(() -> lector.leer(VALIDO.replace("Texto dos.\n", ""), "e.md"))
				.hasMessageContaining("'Segunda' esta vacia");
		assertThatThrownBy(() -> lector.leer(VALIDO.replace("## Segunda", "## Primera"), "f.md"))
				.hasMessageContaining("repetida");
		assertThatThrownBy(() -> lector.leer("---\nid: PROTO-001\ntitulo: T\nversion: 1\n---\n", "g.md"))
				.hasMessageContaining("ninguna seccion");
		assertThatThrownBy(() -> lector.leer("---\nid: PROTO-001\ntitulo: T\nversion: 1\n---\nsuelto\n## S\nx", "h.md"))
				.hasMessageContaining("antes de la primera seccion");
	}

	/**
	 * El corpus de resources/protocolos tiene el contenido IDENTICO al del enunciado (docs/enunciado.md): mismo titulo y
	 * mismo texto de cada apartado. Si el enunciado no esta en el arbol (p. ej. al construir solo el servicio) se omite.
	 */
	@Test
	void elCorpusCoincideConElEnunciado() throws IOException {
		Path enunciado = Path.of("..", "docs", "enunciado.md");
		assumeTrue(Files.exists(enunciado), "docs/enunciado.md no esta disponible");
		Map<String, String> titulos = new LinkedHashMap<>();
		Map<String, Map<String, String>> secciones = new LinkedHashMap<>();
		String actual = null;
		Pattern encabezado = Pattern.compile("^### (PROTO-\\d{3}) — (.+)$");
		Pattern apartado = Pattern.compile("^- \\*\\*([^:*]+):\\*\\* (.+)$");
		for (String linea : Files.readAllLines(enunciado, StandardCharsets.UTF_8)) {
			Matcher h = encabezado.matcher(linea);
			if (h.matches()) {
				actual = h.group(1);
				titulos.put(actual, h.group(2).strip());
				secciones.put(actual, new LinkedHashMap<>());
				continue;
			}
			if (linea.startsWith("#") || linea.startsWith("**Consultas")) {
				actual = null;   // fin del corpus: lo que sigue ya no son apartados de un protocolo
			}
			Matcher a = apartado.matcher(linea);
			if (actual != null && a.matches()) {
				secciones.get(actual).put(a.group(1).strip(), a.group(2).strip());
			}
		}
		assertThat(titulos).containsOnlyKeys("PROTO-001", "PROTO-002", "PROTO-003");

		for (String id : titulos.keySet()) {
			String md = new String(new ClassPathResource("protocolos/" + id + ".md").getInputStream().readAllBytes(),
					StandardCharsets.UTF_8);
			Protocolo p = lector.leer(md, id + ".md");
			assertThat(p.id()).isEqualTo(id);
			assertThat(p.titulo()).as("titulo de %s", id).isEqualTo(titulos.get(id));
			Map<String, String> esperadas = secciones.get(id);
			assertThat(p.secciones()).as("secciones de %s", id).extracting(SeccionProtocolo::titulo)
					.containsExactlyElementsOf(List.copyOf(esperadas.keySet()));
			for (SeccionProtocolo s : p.secciones()) {
				assertThat(s.texto()).as("%s / %s", id, s.titulo()).isEqualTo(esperadas.get(s.titulo()));
			}
		}
	}

}
