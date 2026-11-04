package com.vidacruz.assistant.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vidacruz.assistant.domain.exception.ProtocoloInvalidoException;
import com.vidacruz.assistant.domain.model.Protocolo;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.model.SeccionProtocolo;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.domain.service.FragmentadorProtocolos;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class IndexarProtocolosUseCaseTests {

	FuenteProtocolosPort fuente = mock(FuenteProtocolosPort.class);
	IndiceProtocolosPort indice = mock(IndiceProtocolosPort.class);
	EstadoIndicePort estado = mock(EstadoIndicePort.class);
	IndexarProtocolosUseCaseImpl useCase = new IndexarProtocolosUseCaseImpl(fuente, new FragmentadorProtocolos(), indice, estado);

	private static Protocolo protocolo() {
		return new Protocolo("PROTO-001", "Titulo", "1.0", List.of(new SeccionProtocolo("A", "a"), new SeccionProtocolo("B", "b")));
	}

	@Test
	void indexaUnFragmentoPorSeccionYMarcaElIndiceListo() {
		when(fuente.cargar()).thenReturn(Mono.just(List.of(protocolo())));
		var resultado = new ResultadoSincronizacion(2, 0, 0, 0);
		when(indice.sincronizar(any())).thenReturn(Mono.just(resultado));

		assertThat(useCase.indexar().block()).isEqualTo(resultado);

		verify(indice).sincronizar(new FragmentadorProtocolos().fragmentar(List.of(protocolo())));
		verify(estado).marcarListo(resultado);
	}

	@Test
	void siElIndiceFallaMarcaElFalloYPropagaParaQueSeReintente() {
		when(fuente.cargar()).thenReturn(Mono.just(List.of(protocolo())));
		when(indice.sincronizar(any())).thenReturn(Mono.error(new IllegalStateException("proveedor caido")));

		assertThatThrownBy(() -> useCase.indexar().block()).isInstanceOf(IllegalStateException.class);

		verify(estado).marcarFallo("IllegalStateException: proveedor caido");
	}

	@Test
	void unCorpusVacioEsUnErrorYNoBorraElIndice() {
		when(fuente.cargar()).thenReturn(Mono.just(List.of()));

		assertThatThrownBy(() -> useCase.indexar().block()).isInstanceOf(ProtocoloInvalidoException.class);

		verifyNoInteractions(indice);
	}

	@Test
	void unProtocoloMalFormadoImpideIndexar() {
		when(fuente.cargar()).thenReturn(Mono.error(new ProtocoloInvalidoException("Protocolo invalido (x.md): ...")));

		assertThatThrownBy(() -> useCase.indexar().block()).isInstanceOf(ProtocoloInvalidoException.class);

		verifyNoInteractions(indice);
	}

	@Test
	void conLaIndexacionDeshabilitadaSeAsumeElIndiceExistente() {
		useCase.asumirIndiceExistente();

		verify(estado).marcarListo(new ResultadoSincronizacion(0, 0, 0, 0));
	}

}
