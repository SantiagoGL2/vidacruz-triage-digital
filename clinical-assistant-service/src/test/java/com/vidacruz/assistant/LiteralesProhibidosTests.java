package com.vidacruz.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.enums.EstadoPaciente;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.domain.model.enums.TipoEventoClinico;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Convencion de literales (ver CLAUDE.md, «Literales»): en el codigo de produccion no se escribe a mano
 * {@code "application/problem+json"} (se usa {@code MediaType.APPLICATION_PROBLEM_JSON_VALUE}) ni el nombre de un valor de
 * dominio como texto ({@code "CRITICO"}, {@code "EN_CONSULTA"}, {@code "paciente_escalado"}...): se usa el enum.
 * <p>
 * Solo se miran literales de cadena completos (no comentarios, ni bloques de texto, ni fragmentos dentro de otro texto),
 * asi que no hay falsos positivos por documentacion o por mensajes. Excepciones justificadas en {@link #EXCEPCIONES}.
 */
class LiteralesProhibidosTests {

	/** Archivos que pueden contener esos textos, y por que. */
	private static final Set<String> EXCEPCIONES = Set.of(
			// Ejemplos JSON de la documentacion: son datos de muestra, no logica.
			"entrypoint/web/controller/doc/EjemplosApi.java",
			// Los enums son quien define esos textos.
			"domain/model/enums/NivelTriaje.java", "domain/model/enums/EstadoPaciente.java",
			"domain/model/enums/TipoEventoClinico.java",
			// Codigos de operacion del protocolo con aplicar-evento.lua (OP_ALTA...): no son valores del dominio.
			"redis/adapter/EstadoUrgenciasRedisAdapter.java");

	@Test
	void produccionNoEscribeLiteralesDelFrameworkNiValoresDeDominio() throws IOException {
		Set<String> prohibidos = new LinkedHashSet<>();
		prohibidos.add("application/problem+json");
		Arrays.stream(NivelTriaje.values()).forEach(v -> prohibidos.add(v.name()));
		Arrays.stream(EstadoPaciente.values()).forEach(v -> prohibidos.add(v.name()));
		Arrays.stream(TipoEventoClinico.values()).forEach(v -> prohibidos.add(v.codigo()));

		List<String> infracciones = new ArrayList<>();
		try (Stream<Path> archivos = Files.walk(Path.of("src/main/java"))) {
			for (Path archivo : archivos.filter(p -> p.toString().endsWith(".java")).toList()) {
				String ruta = archivo.toString().replace('\\', '/');
				if (EXCEPCIONES.stream().anyMatch(ruta::endsWith)) {
					continue;
				}
				for (String literal : literales(Files.readString(archivo))) {
					if (prohibidos.contains(literal)) {
						infracciones.add(ruta + " -> \"" + literal + "\"");
					}
				}
			}
		}
		assertThat(infracciones)
				.as("Usa MediaType.*_VALUE o el enum de dominio en vez del literal (ver CLAUDE.md, «Literales»)")
				.isEmpty();
	}

	@Test
	void elEscanerDetectaLosLiteralesYNoMiraComentariosNiBloquesDeTexto() {
		String fuente = """
				// "CRITICO"
				/* "ALTA" */
				String a = "URGENTE"; String b = "https://x";
				String c = \"\"\"
				  "NO_URGENTE"
				  \"\"\";
				char d = '"'; String e = "con \\" comilla";
				""";
		assertThat(literales(fuente)).containsExactly("URGENTE", "https://x", "con \\\" comilla");
	}

	/** Literales de cadena de un fuente Java, sin comentarios ni bloques de texto. */
	static List<String> literales(String fuente) {
		List<String> resultado = new ArrayList<>();
		int i = 0;
		int n = fuente.length();
		while (i < n) {
			char c = fuente.charAt(i);
			if (fuente.startsWith("//", i)) {
				i = fuente.indexOf('\n', i);
				if (i < 0) {
					break;
				}
			}
			else if (fuente.startsWith("/*", i)) {
				i = fuente.indexOf("*/", i + 2);
				if (i < 0) {
					break;
				}
				i += 2;
			}
			else if (fuente.startsWith("\"\"\"", i)) {
				int fin = i + 3;
				while (fin < n && !(fuente.startsWith("\"\"\"", fin) && fuente.charAt(fin - 1) != '\\')) {
					fin++;
				}
				i = fin + 3;
			}
			else if (c == '"') {
				int j = i + 1;
				while (j < n && fuente.charAt(j) != '"') {
					j += fuente.charAt(j) == '\\' ? 2 : 1;
				}
				resultado.add(fuente.substring(i + 1, j));
				i = j + 1;
			}
			else if (c == '\'') {
				int j = i + 1;
				while (j < n && fuente.charAt(j) != '\'') {
					j += fuente.charAt(j) == '\\' ? 2 : 1;
				}
				i = j + 1;
			}
			else {
				i++;
			}
		}
		return resultado;
	}

}
