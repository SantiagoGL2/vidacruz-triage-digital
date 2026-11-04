package com.vidacruz.assistant.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.assistant.application.command.AplicarEventoComando;
import com.vidacruz.assistant.application.result.ResultadoProcesamiento;
import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.domain.model.AplicacionEvento;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.OperacionEstado;
import com.vidacruz.assistant.domain.model.VistaUrgencias;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import com.vidacruz.assistant.domain.service.ProyectorUrgencias;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** El caso de uso con un almacen en memoria (la propia VistaUrgencias del dominio) y metricas espia. */
class AplicarEventoClinicoUseCaseTests {

	private static final Instant CUANDO = Instant.parse("2026-11-04T15:00:00Z");

	/** Almacen en memoria: aplica con las reglas del dominio. */
	static class AlmacenEnMemoria implements EstadoUrgenciasPort {

		VistaUrgencias vista = VistaUrgencias.vacia();

		@Override
		public Mono<AplicacionEvento> aplicar(UUID eventId, Instant ocurridoEn, OperacionEstado operacion) {
			var r = vista.aplicar(eventId, ocurridoEn, operacion);
			vista = r.vista();
			return Mono.just(new AplicacionEvento(r.resultado(), vista.estado()));
		}

		@Override
		public Mono<EstadoUrgencias> consultar() {
			return Mono.just(vista.estado());
		}

	}

	static class MetricasEspia implements MetricasPort {

		final List<String> procesados = new ArrayList<>();
		final List<String> ignorados = new ArrayList<>();
		int duplicados;
		int dlt;
		EstadoUrgencias ultimaVista;

		@Override
		public void eventoProcesado(String tipoEvento) {
			procesados.add(tipoEvento);
		}

		@Override
		public void eventoDuplicado() {
			duplicados++;
		}

		@Override
		public void eventoIgnorado(String motivo) {
			ignorados.add(motivo);
		}

		@Override
		public void eventoEnviadoADlt() {
			dlt++;
		}

		@Override
		public void vistaActualizada(EstadoUrgencias vista) {
			ultimaVista = vista;
		}

	}

	private final AlmacenEnMemoria almacen = new AlmacenEnMemoria();
	private final MetricasEspia metricas = new MetricasEspia();
	private final AplicarEventoClinicoUseCaseImpl useCase =
			new AplicarEventoClinicoUseCaseImpl(new ProyectorUrgencias(), almacen, metricas);

	private AplicarEventoComando comando(String tipo, UUID paciente, String nivel) {
		return new AplicarEventoComando(UUID.randomUUID(), tipo, CUANDO, "c-1", paciente.toString(), nivel,
				"2026-11-04T15:00:00Z");
	}

	@Test
	void unRegistradoSeAplicaYCuentaComoProcesado() {
		UUID paciente = UUID.randomUUID();

		var r = useCase.aplicar(comando("paciente_registrado", paciente, "URGENTE")).block();

		assertThat(r).isEqualTo(ResultadoProcesamiento.APLICADO);
		assertThat(metricas.procesados).containsExactly("paciente_registrado");
		assertThat(metricas.ultimaVista.totalActivos()).isEqualTo(1);
	}

	@Test
	void unTipoDesconocidoSeIgnoraSinError() {
		var r = useCase.aplicar(comando("paciente_trasladado", UUID.randomUUID(), null)).block();

		assertThat(r).isEqualTo(ResultadoProcesamiento.IGNORADO_TIPO_DESCONOCIDO);
		assertThat(metricas.ignorados).containsExactly("tipo_desconocido");
		assertThat(almacen.vista.estado().totalActivos()).isZero();
	}

	@Test
	void unTipoDesconocidoNoExigeUnPayloadValido() {
		var cmd = new AplicarEventoComando(UUID.randomUUID(), "evento_futuro", CUANDO, null, null, null, null);

		assertThat(useCase.aplicar(cmd).block()).isEqualTo(ResultadoProcesamiento.IGNORADO_TIPO_DESCONOCIDO);
	}

	@Test
	void unEventoConocidoIncompletoFallaConEventoInvalido() {
		var cmd = new AplicarEventoComando(UUID.randomUUID(), "paciente_registrado", CUANDO, null, null, null, null);

		assertThatThrownBy(() -> useCase.aplicar(cmd).block()).isInstanceOf(EventoInvalidoException.class);
		assertThat(metricas.procesados).isEmpty();
	}

	@Test
	void sinEventTypeEsInvalido() {
		var cmd = new AplicarEventoComando(UUID.randomUUID(), null, CUANDO, null, UUID.randomUUID().toString(), null, null);

		assertThatThrownBy(() -> useCase.aplicar(cmd).block()).isInstanceOf(EventoInvalidoException.class)
				.hasMessageContaining("eventType");
	}

	@Test
	void elMismoEventoDosVecesSeAplicaUnaVezYElSegundoCuentaComoDuplicado() {
		var cmd = comando("paciente_registrado", UUID.randomUUID(), "CRITICO");

		var primera = useCase.aplicar(cmd).block();
		var segunda = useCase.aplicar(cmd).block();

		assertThat(primera).isEqualTo(ResultadoProcesamiento.APLICADO);
		assertThat(segunda).isEqualTo(ResultadoProcesamiento.DUPLICADO);
		assertThat(metricas.duplicados).isEqualTo(1);
		assertThat(almacen.vista.estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void unaAltaDeUnPacienteDesconocidoSeIgnoraConMetrica() {
		var r = useCase.aplicar(comando("paciente_alta", UUID.randomUUID(), null)).block();

		assertThat(r).isEqualTo(ResultadoProcesamiento.IGNORADO_PACIENTE_DESCONOCIDO);
		assertThat(metricas.ignorados).containsExactly("paciente_desconocido");
		assertThat(almacen.vista.estado().porNivel().values()).allMatch(n -> n == 0);
	}

	@Test
	void unRegistroRepetidoDelMismoPacienteSeIgnora() {
		UUID paciente = UUID.randomUUID();
		useCase.aplicar(comando("paciente_registrado", paciente, "CRITICO")).block();

		var r = useCase.aplicar(comando("paciente_registrado", paciente, "CRITICO")).block();

		assertThat(r).isEqualTo(ResultadoProcesamiento.IGNORADO_YA_REGISTRADO);
		assertThat(metricas.ignorados).containsExactly("ya_registrado");
		assertThat(almacen.vista.estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void secuenciaCompletaDejaLaVistaVacia() {
		UUID paciente = UUID.randomUUID();
		useCase.aplicar(comando("paciente_registrado", paciente, "URGENTE_2")).block();
		useCase.aplicar(comando("paciente_en_consulta", paciente, null)).block();
		useCase.aplicar(comando("paciente_escalado", paciente, null)).block();
		useCase.aplicar(comando("paciente_alta", paciente, null)).block();

		assertThat(almacen.vista.estado().totalActivos()).isZero();
		assertThat(almacen.vista.estado().escalados()).isZero();
		assertThat(metricas.procesados).containsExactly("paciente_registrado", "paciente_en_consulta",
				"paciente_escalado", "paciente_alta");
	}

}
