package com.vidacruz.assistant.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import java.util.List;
import org.junit.jupiter.api.Test;

class FragmentadorProtocolosTests {

	private final FragmentadorProtocolos fragmentador = new FragmentadorProtocolos();

	private static Protocolo protocolo(String version, String textoMedidas) {
		return new Protocolo("PROTO-001", "Crisis Hipertensiva, manejo inicial", version, List.of(
				new SeccionProtocolo("Definición", "Presión arterial ≥ 180/120 mmHg."),
				new SeccionProtocolo("Medidas iniciales", textoMedidas)));
	}

	@Test
	void unFragmentoPorSeccionConMetadatosYTituloEnElTexto() {
		List<FragmentoProtocolo> fragmentos = fragmentador.fragmentar(protocolo("1.0", "Reconfirmar la presión."));

		assertThat(fragmentos).hasSize(2);
		FragmentoProtocolo medidas = fragmentos.get(1);
		assertThat(medidas.protocolo()).isEqualTo("PROTO-001");
		assertThat(medidas.titulo()).isEqualTo("Crisis Hipertensiva, manejo inicial");
		assertThat(medidas.seccion()).isEqualTo("Medidas iniciales");
		assertThat(medidas.version()).isEqualTo("1.0");
		assertThat(medidas.texto()).contains("PROTO-001").contains("Crisis Hipertensiva, manejo inicial")
				.contains("Medidas iniciales").contains("Reconfirmar la presión.");
	}

	@Test
	void elIdEsDeterministaYLegible() {
		var a = fragmentador.fragmentar(protocolo("1.0", "x")).get(1);
		var b = fragmentador.fragmentar(protocolo("1.0", "x")).get(1);

		assertThat(a.id()).isEqualTo("proto-001:medidas-iniciales").isEqualTo(b.id());
		assertThat(fragmentador.fragmentar(protocolo("1.0", "x")).get(0).id()).isEqualTo("proto-001:definicion");
	}

	@Test
	void elHashCambiaSoloSiCambiaElContenidoOLaVersion() {
		String hashBase = fragmentador.fragmentar(protocolo("1.0", "x")).get(1).hash();

		assertThat(fragmentador.fragmentar(protocolo("1.0", "x")).get(1).hash()).isEqualTo(hashBase);
		assertThat(fragmentador.fragmentar(protocolo("1.0", "otro texto")).get(1).hash()).isNotEqualTo(hashBase);
		assertThat(fragmentador.fragmentar(protocolo("1.1", "x")).get(1).hash()).isNotEqualTo(hashBase);
		// la otra seccion no cambio: su hash tampoco
		assertThat(fragmentador.fragmentar(protocolo("1.0", "otro texto")).get(0).hash())
				.isEqualTo(fragmentador.fragmentar(protocolo("1.0", "x")).get(0).hash());
	}

}
