package com.vidacruz.assistant.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidacruz.assistant.application.command.ConsultarProtocoloComando;
import com.vidacruz.assistant.application.result.RespuestaConsulta;
import com.vidacruz.assistant.domain.exception.AsistenteNoDisponibleException;
import com.vidacruz.assistant.domain.exception.IndiceNoDisponibleException;
import com.vidacruz.assistant.domain.model.CitaProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.ParametrosRecuperacion;
import com.vidacruz.assistant.domain.model.RespuestaGenerada;
import com.vidacruz.assistant.domain.model.SalidaGenerador;
import com.vidacruz.assistant.domain.port.out.BuscadorProtocolosPort;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.GeneradorRespuestaPort;
import com.vidacruz.assistant.domain.port.out.MetricasRagPort;
import com.vidacruz.assistant.domain.service.ValidadorRespuesta;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Las tres barreras de RN-05 con los puertos mockeados (sin Redis, sin LLM, sin red). */
class ConsultarProtocoloUseCaseTests {

	static final String SIN_PROTOCOLO = "No existe un protocolo disponible en el corpus oficial para responder esta pregunta.";
	static final ParametrosRecuperacion PARAMETROS = new ParametrosRecuperacion(4, 0.65);

	BuscadorProtocolosPort buscador = mock(BuscadorProtocolosPort.class);
	GeneradorRespuestaPort generador = mock(GeneradorRespuestaPort.class);
	EstadoIndicePort estadoIndice = mock(EstadoIndicePort.class);
	MetricasRagPort metricas = mock(MetricasRagPort.class);
	ConsultarProtocoloUseCaseImpl useCase;

	@BeforeEach
	void preparar() {
		when(estadoIndice.listo()).thenReturn(true);
		useCase = new ConsultarProtocoloUseCaseImpl(buscador, generador, new ValidadorRespuesta(), estadoIndice, metricas,
				PARAMETROS, SIN_PROTOCOLO);
	}

	private static FragmentoRecuperado fragmento(String protocolo, String seccion, double puntuacion) {
		return new FragmentoRecuperado(new FragmentoProtocolo(protocolo + ":" + seccion, protocolo, "Titulo", seccion, "1.0",
				"texto de " + seccion, "hash"), puntuacion);
	}

	private void recupera(FragmentoRecuperado... fragmentos) {
		when(buscador.buscar(any(), any())).thenReturn(Mono.just(List.of(fragmentos)));
	}

	private void generaGenerada(boolean respondida, String texto, CitaProtocolo... citas) {
		when(generador.generar(any(), any())).thenReturn(
				Mono.just(new SalidaGenerador.Generada(new RespuestaGenerada(respondida, texto, List.of(citas)))));
	}

	private RespuestaConsulta consultar() {
		return useCase.consultar(new ConsultarProtocoloComando("¿Cuáles son las medidas iniciales ante una crisis hipertensiva?")).block();
	}

	private void assertSinProtocolo(RespuestaConsulta r) {
		assertThat(r.respuesta()).isEqualTo(SIN_PROTOCOLO);
		assertThat(r.fuentes()).isEmpty();
	}

	@Test
	void sinFragmentosSobreElUmbralResponde_SinProtocolo_YNoInvocaAlGenerador() {
		recupera();

		RespuestaConsulta r = consultar();

		assertSinProtocolo(r);
		verifyNoInteractions(generador);
		verify(metricas).consulta("sin_protocolo");
	}

	@Test
	void laBusquedaRecibeTopKYUmbralConfigurados() {
		recupera();

		consultar();

		verify(buscador).buscar("¿Cuáles son las medidas iniciales ante una crisis hipertensiva?", PARAMETROS);
	}

	@Test
	void unaCitaInexistenteEnLoRecuperadoSeDescarta_YSiNoQuedaNingunaEsSinProtocolo() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		generaGenerada(true, "Contenido clinico inventado.", new CitaProtocolo("PROTO-009", "Inventada"));

		RespuestaConsulta r = consultar();

		assertSinProtocolo(r);
		verify(metricas).citasDescartadas(1);
		verify(metricas).consulta("sin_protocolo");
	}

	@Test
	void unaCitaInexistenteSeDescartaPeroLaValidaSeConserva() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		generaGenerada(true, "Reconfirmar la presión.", new CitaProtocolo("PROTO-009", "Inventada"),
				new CitaProtocolo("PROTO-001", "Medidas iniciales"));

		RespuestaConsulta r = consultar();

		assertThat(r.respuesta()).isEqualTo("Reconfirmar la presión.");
		assertThat(r.fuentes()).containsExactly(new CitaProtocolo("PROTO-001", "Medidas iniciales"));
		verify(metricas).citasDescartadas(1);
		verify(metricas).consulta("respondida");
	}

	@Test
	void respondidaFalsaEsSinProtocoloAunqueElTextoTraigaContenido() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		generaGenerada(false, "Se recomienda dar nifedipino sublingual.", new CitaProtocolo("PROTO-001", "Medidas iniciales"));

		assertSinProtocolo(consultar());
	}

	@Test
	void unaSalidaNoParseableEsSinProtocolo() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		when(generador.generar(any(), any())).thenReturn(Mono.just(new SalidaGenerador.NoParseable("JSON invalido")));

		assertSinProtocolo(consultar());
		verify(metricas).consulta("sin_protocolo");
	}

	@Test
	void lasFuentesCoincidenExactamenteConLasCitasValidadas() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.95), fragmento("PROTO-001", "Definición", 0.9),
				fragmento("PROTO-001", "Contraindicaciones", 0.8));
		generaGenerada(true, "Texto.", new CitaProtocolo("PROTO-001", "Medidas iniciales"),
				new CitaProtocolo("PROTO-002", "Criterios"), new CitaProtocolo("PROTO-001", "Contraindicaciones"));

		RespuestaConsulta r = consultar();

		assertThat(r.fuentes()).containsExactly(new CitaProtocolo("PROTO-001", "Medidas iniciales"),
				new CitaProtocolo("PROTO-001", "Contraindicaciones"));
	}

	@Test
	void alGeneradorSoloLeLlegaLoRecuperado() {
		var recuperado = fragmento("PROTO-001", "Medidas iniciales", 0.9);
		recupera(recuperado);
		generaGenerada(true, "Texto.", new CitaProtocolo("PROTO-001", "Medidas iniciales"));

		consultar();

		verify(generador).generar("¿Cuáles son las medidas iniciales ante una crisis hipertensiva?", List.of(recuperado));
	}

	@Test
	void sinIndiceListoFallaConIndiceNoDisponibleYNoBuscaNiGenera() {
		when(estadoIndice.listo()).thenReturn(false);
		when(estadoIndice.motivo()).thenReturn("indexando");

		assertThatThrownBy(this::consultar).isInstanceOf(IndiceNoDisponibleException.class).hasMessageContaining("indexando");
		verifyNoInteractions(buscador, generador);
		verify(metricas).consulta("degradado");
	}

	@Test
	void siElProveedorDeEmbeddingsFallaSePropagaComoModoDegradado() {
		when(buscador.buscar(any(), any())).thenReturn(Mono.error(new AsistenteNoDisponibleException("embedding", "timeout", null)));

		assertThatThrownBy(this::consultar).isInstanceOf(AsistenteNoDisponibleException.class);
		verify(generador, never()).generar(any(), any());
		verify(metricas).consulta("degradado");
	}

	@Test
	void siElProveedorDeChatFallaSePropagaComoModoDegradado() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		when(generador.generar(any(), any())).thenReturn(Mono.error(new AsistenteNoDisponibleException("chat", "circuito abierto", null)));

		assertThatThrownBy(this::consultar).isInstanceOf(AsistenteNoDisponibleException.class);
		verify(metricas).consulta("degradado");
		verify(metricas, never()).consulta("respondida");
	}

	@Test
	void lasFallasDeValidacionNoSonModoDegradado() {
		recupera(fragmento("PROTO-001", "Medidas iniciales", 0.9));
		generaGenerada(true, "Texto.", new CitaProtocolo("PROTO-009", "X"));

		consultar();

		verify(metricas, never()).consulta("degradado");
	}

}
