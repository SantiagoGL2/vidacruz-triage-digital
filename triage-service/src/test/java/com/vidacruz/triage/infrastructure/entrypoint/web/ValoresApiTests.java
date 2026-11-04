package com.vidacruz.triage.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.model.enums.NivelTriaje;
import com.vidacruz.triage.infrastructure.entrypoint.web.dto.ValoresApi;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Las constantes de {@link ValoresApi} (para anotaciones de OpenAPI) deben coincidir con los enums de dominio. */
class ValoresApiTests {

	@Test
	void coincidenConLosEnums() {
		List<String> constantes = Arrays.stream(ValoresApi.class.getDeclaredFields())
				.filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
				.map(f -> {
					try {
						return (String) f.get(null);
					}
					catch (IllegalAccessException e) {
						throw new IllegalStateException(e);
					}
				}).toList();
		List<String> esperadas = Arrays.stream(NivelTriaje.values()).map(Enum::name).toList();
		List<String> estados = Arrays.stream(EstadoPaciente.values()).map(Enum::name).toList();
		assertThat(constantes).containsExactlyInAnyOrderElementsOf(
				java.util.stream.Stream.concat(esperadas.stream(), estados.stream()).toList());
	}

}
