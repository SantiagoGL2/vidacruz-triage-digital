package com.vidacruz.assistant.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.CitaProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.RespuestaGenerada;
import com.vidacruz.assistant.domain.model.ValidacionRespuesta;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Barrera 3 de RN-05: lo que dice el modelo no es confiable hasta que el dominio lo valida. */
class ValidadorRespuestaTests {

	private final ValidadorRespuesta validador = new ValidadorRespuesta();

	private static FragmentoRecuperado recuperado(String protocolo, String seccion) {
		return new FragmentoRecuperado(new FragmentoProtocolo(protocolo + ":" + seccion, protocolo, "Titulo", seccion, "1.0",
				"texto", "hash"), 0.9);
	}

	private static final List<FragmentoRecuperado> RECUPERADOS = List.of(
			recuperado("PROTO-001", "Medidas iniciales"), recuperado("PROTO-001", "Definición"));

	@Test
	void unaCitaDeUnFragmentoRecuperadoEsValidaYSeUsaSuNombreCanonico() {
		var generada = new RespuestaGenerada(true, "  Texto clinico.  ",
				List.of(new CitaProtocolo("proto-001", "MEDIDAS INICIALES")));   // otra grafia: se normaliza

		ValidacionRespuesta v = validador.validar(generada, RECUPERADOS);

		assertThat(v.valida()).isTrue();
		assertThat(v.respuesta()).isEqualTo("Texto clinico.");
		assertThat(v.citasValidas()).containsExactly(new CitaProtocolo("PROTO-001", "Medidas iniciales"));
		assertThat(v.citasDescartadas()).isZero();
	}

	@Test
	void seDescartanLasCitasInventadasYLasDeFragmentosNoRecuperados() {
		var generada = new RespuestaGenerada(true, "Texto.", List.of(
				new CitaProtocolo("PROTO-009", "Inventada"),                 // protocolo que no existe
				new CitaProtocolo("PROTO-003", "Rehidratación"),             // existe en el corpus, pero no se recupero
				new CitaProtocolo("PROTO-001", "Contraindicaciones"),        // protocolo recuperado, seccion no recuperada
				new CitaProtocolo("PROTO-001", "Definición")));              // esta si

		ValidacionRespuesta v = validador.validar(generada, RECUPERADOS);

		assertThat(v.valida()).isTrue();
		assertThat(v.citasValidas()).containsExactly(new CitaProtocolo("PROTO-001", "Definición"));
		assertThat(v.citasPropuestas()).isEqualTo(4);
		assertThat(v.citasDescartadas()).isEqualTo(3);
	}

	@Test
	void sinNingunaCitaValidaNoHayRespuesta() {
		var generada = new RespuestaGenerada(true, "Texto clinico inventado.", List.of(new CitaProtocolo("PROTO-009", "X")));

		ValidacionRespuesta v = validador.validar(generada, RECUPERADOS);

		assertThat(v.valida()).isFalse();
		assertThat(v.respuesta()).isNull();
		assertThat(v.citasDescartadas()).isEqualTo(1);
	}

	@Test
	void sinCitasNoHayRespuesta() {
		assertThat(validador.validar(new RespuestaGenerada(true, "Texto.", List.of()), RECUPERADOS).valida()).isFalse();
		assertThat(validador.validar(new RespuestaGenerada(true, "Texto.", null), RECUPERADOS).valida()).isFalse();
	}

	@Test
	void respondidaFalsaNoEsValidaAunqueTraigaTextoYCitasValidas() {
		var generada = new RespuestaGenerada(false, "Texto clinico igualmente.",
				List.of(new CitaProtocolo("PROTO-001", "Medidas iniciales")));

		ValidacionRespuesta v = validador.validar(generada, RECUPERADOS);

		assertThat(v.valida()).isFalse();
		assertThat(v.respuesta()).isNull();
	}

	@Test
	void unaRespuestaEnBlancoNoEsValida() {
		var generada = new RespuestaGenerada(true, "   ", List.of(new CitaProtocolo("PROTO-001", "Medidas iniciales")));

		assertThat(validador.validar(generada, RECUPERADOS).valida()).isFalse();
	}

	@Test
	void lasCitasRepetidasSeUnifican() {
		var generada = new RespuestaGenerada(true, "Texto.", List.of(
				new CitaProtocolo("PROTO-001", "Medidas iniciales"), new CitaProtocolo("proto-001", "medidas iniciales")));

		assertThat(validador.validar(generada, RECUPERADOS).citasValidas()).hasSize(1);
	}

	@Test
	void lasCitasNulasSeIgnoran() {
		var citas = new java.util.ArrayList<CitaProtocolo>();
		citas.add(null);
		citas.add(new CitaProtocolo("PROTO-001", "Medidas iniciales"));

		ValidacionRespuesta v = validador.validar(new RespuestaGenerada(true, "Texto.", citas), RECUPERADOS);

		assertThat(v.valida()).isTrue();
		assertThat(v.citasValidas()).hasSize(1);
	}

}
