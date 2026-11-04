package com.vidacruz.assistant.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.assistant.domain.exception.EventoInvalidoException;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.domain.model.enums.TipoEventoClinico;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventoClinicoTests {

	private static final UUID EVENTO = UUID.randomUUID();
	private static final Instant CUANDO = Instant.parse("2026-11-04T15:00:00Z");
	private static final String PACIENTE = "e62f5bb0-848c-4343-9887-19cc8fcf3a0a";

	@Test
	void registradoExigeNivelYFechaDeRegistro() {
		var e = EventoClinico.crear(EVENTO, TipoEventoClinico.PACIENTE_REGISTRADO, CUANDO, "c1", PACIENTE, "CRITICO",
				"2026-11-04T15:00:00Z");

		assertThat(e.nivel()).isEqualTo(NivelTriaje.CRITICO);
		assertThat(e.fechaRegistro()).isEqualTo(CUANDO);
		assertThat(e.pacienteId()).isEqualTo(UUID.fromString(PACIENTE));
	}

	@Test
	void lasDemasTransicionesSoloNecesitanElPaciente() {
		for (var tipo : new TipoEventoClinico[] {TipoEventoClinico.PACIENTE_EN_CONSULTA,
				TipoEventoClinico.PACIENTE_ESCALADO, TipoEventoClinico.PACIENTE_ALTA}) {
			var e = EventoClinico.crear(EVENTO, tipo, CUANDO, null, PACIENTE, null, null);

			assertThat(e.pacienteId()).isEqualTo(UUID.fromString(PACIENTE));
			assertThat(e.nivel()).isNull();
		}
	}

	@Test
	void unRegistradoIncompletoListaTodosLosErrores() {
		assertThatThrownBy(() -> EventoClinico.crear(EVENTO, TipoEventoClinico.PACIENTE_REGISTRADO, CUANDO, null,
				"no-es-uuid", "MUY_GRAVE", "ayer"))
				.isInstanceOf(EventoInvalidoException.class)
				.hasMessageContaining("pacienteId no es un UUID")
				.hasMessageContaining("nivel desconocido")
				.hasMessageContaining("fechaRegistro no es una fecha");
	}

	@Test
	void faltaElPacienteOLosDatosDelEnvelope() {
		assertThatThrownBy(() -> EventoClinico.crear(null, TipoEventoClinico.PACIENTE_ALTA, null, null, null, null, null))
				.isInstanceOf(EventoInvalidoException.class)
				.hasMessageContaining("eventId obligatorio")
				.hasMessageContaining("occurredAt obligatorio")
				.hasMessageContaining("pacienteId obligatorio");
	}

	@Test
	void tiposDesconocidosNoSeParsean() {
		assertThat(TipoEventoClinico.desdeCodigo("paciente_alta")).contains(TipoEventoClinico.PACIENTE_ALTA);
		assertThat(TipoEventoClinico.desdeCodigo("paciente_trasladado")).isEmpty();
		assertThat(TipoEventoClinico.desdeCodigo(null)).isEmpty();
	}

}
