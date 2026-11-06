package com.vidacruz.triage.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.model.RangoClinico;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.request.RegistrarPacienteRequest;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.PacienteResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Una sola fuente de verdad por rango: los limites de {@code @Schema} (minimum/maximum, minLength/maxLength) de los DTOs deben
 * ser los de {@link RangoClinico}, que es lo que valida el dominio. (Hoy ya salen de las mismas constantes; este test evita que
 * alguien vuelva a escribir un numero a mano en una anotacion.)
 */
class RangosOpenApiTests {

	private static List<RecordComponent> componentes(Class<?> tipo) {
		List<RecordComponent> lista = new ArrayList<>(List.of(tipo.getRecordComponents()));
		for (Class<?> anidado : tipo.getDeclaredClasses()) {
			if (anidado.isRecord()) {
				lista.addAll(componentes(anidado));
			}
		}
		return lista;
	}

	private static void verificar(Class<?> dto, boolean debenEstarTodos) {
		List<String> encontrados = new ArrayList<>();
		for (RecordComponent c : componentes(dto)) {
			for (RangoClinico rango : RangoClinico.values()) {
				// la respuesta no declara el largo del nombre (nunca lo hizo): solo se exige en la peticion
				if (!rango.campo().equals(c.getName()) || (rango == RangoClinico.NOMBRE && !debenEstarTodos)) {
					continue;
				}
				Schema schema = c.getAccessor().getAnnotation(Schema.class);
				assertThat(schema).as("%s.%s necesita @Schema", dto.getSimpleName(), c.getName()).isNotNull();
				if (rango == RangoClinico.NOMBRE) {
					assertThat(schema.minLength()).as("minLength de %s", c.getName()).isEqualTo((int) rango.minimo());
					assertThat(schema.maxLength()).as("maxLength de %s", c.getName()).isEqualTo((int) rango.maximo());
				}
				else {
					assertThat(Double.parseDouble(schema.minimum())).as("minimum de %s", c.getName()).isEqualTo(rango.minimo());
					assertThat(Double.parseDouble(schema.maximum())).as("maximum de %s", c.getName()).isEqualTo(rango.maximo());
				}
				encontrados.add(rango.campo());
			}
		}
		if (debenEstarTodos) {
			assertThat(encontrados).containsExactlyInAnyOrderElementsOf(
					java.util.Arrays.stream(RangoClinico.values()).map(RangoClinico::campo).toList());
		}
		else {
			assertThat(encontrados).isNotEmpty();
		}
	}

	@Test
	void laPeticionDeRegistroDocumentaLosRangosDelDominio() {
		verificar(RegistrarPacienteRequest.class, true);
	}

	@Test
	void laRespuestaDelPacienteDocumentaLosRangosDelDominio() {
		verificar(PacienteResponse.class, false);
	}

}
