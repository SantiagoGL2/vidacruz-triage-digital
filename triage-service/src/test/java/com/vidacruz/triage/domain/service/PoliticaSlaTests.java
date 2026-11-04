package com.vidacruz.triage.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage.domain.model.NivelTriaje;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PoliticaSlaTests {

	@ParameterizedTest
	@CsvSource({"CRITICO,5", "URGENTE,10", "URGENTE_2,30", "NO_URGENTE,60", "NO_URGENTE_2,120"})
	void valoresPorDefectoDelEnunciado(NivelTriaje nivel, int minutos) {
		assertThat(new PoliticaSlaEstandar().minutosPara(nivel)).isEqualTo(minutos);
	}

	@Test
	void exigeTodosLosNivelesYValoresPositivos() {
		assertThatThrownBy(() -> new PoliticaSlaEstandar(Map.of(NivelTriaje.CRITICO, 5)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PoliticaSlaEstandar(Map.of(NivelTriaje.CRITICO, 0, NivelTriaje.URGENTE, 10,
				NivelTriaje.URGENTE_2, 30, NivelTriaje.NO_URGENTE, 60, NivelTriaje.NO_URGENTE_2, 120)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void nivelesTienenSuNumero() {
		assertThat(NivelTriaje.CRITICO.numero()).isEqualTo(1);
		assertThat(NivelTriaje.NO_URGENTE_2.numero()).isEqualTo(5);
		assertThat(NivelTriaje.desdeNumero(3)).isEqualTo(NivelTriaje.URGENTE_2);
		assertThatThrownBy(() -> NivelTriaje.desdeNumero(6)).isInstanceOf(IllegalArgumentException.class);
	}

}
