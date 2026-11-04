package com.vidacruz.assistant.infrastructure.drivenadapter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.OperacionEstado;
import com.vidacruz.assistant.domain.model.ResultadoAplicacion;
import com.vidacruz.assistant.domain.model.VistaUrgencias;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** El script Lua de Redis: atomico, idempotente y equivalente a las reglas del dominio. */
class ScriptLuaTests extends AbstractIntegrationTest {

	private static final Instant T0 = Instant.parse("2026-11-04T15:00:00Z");

	private static OperacionEstado.Registrar registrar(UUID id, NivelTriaje nivel) {
		return new OperacionEstado.Registrar(id, nivel, T0);
	}

	@Test
	void muchosEventosDistintosAplicadosEnParaleloDanElTotalCorrecto() {
		int n = 300;
		NivelTriaje[] niveles = NivelTriaje.values();
		List<UUID> pacientes = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			pacientes.add(UUID.randomUUID());
		}

		Flux.range(0, n)
				.flatMap(i -> vista.aplicar(UUID.randomUUID(), T0.plusSeconds(i), registrar(pacientes.get(i), niveles[i % 5])), 64)
				.blockLast(Duration.ofSeconds(60));

		EstadoUrgencias lleno = estado();
		assertThat(lleno.totalActivos()).isEqualTo(n);
		for (NivelTriaje nivel : niveles) {
			assertThat(lleno.porNivel().get(nivel)).as("nivel %s", nivel).isEqualTo(60L);
		}
		assertThat(lleno.ultimoEventoEn()).isEqualTo(T0.plusSeconds(n - 1));

		// y todas las altas, tambien en paralelo, lo dejan en cero (sin negativos)
		Flux.range(0, n)
				.flatMap(i -> vista.aplicar(UUID.randomUUID(), T0.plusSeconds(1000 + i), new OperacionEstado.DarDeAlta(pacientes.get(i))), 64)
				.blockLast(Duration.ofSeconds(60));

		EstadoUrgencias vacio = estado();
		assertThat(vacio.totalActivos()).isZero();
		assertThat(vacio.porNivel().values()).containsOnly(0L);
	}

	@Test
	void elMismoEventIdAplicadoEnParaleloSoloCuentaUnaVez() {
		UUID eventId = UUID.randomUUID();
		UUID paciente = UUID.randomUUID();

		var resultados = Flux.range(0, 40)
				.flatMap(i -> vista.aplicar(eventId, T0, registrar(paciente, NivelTriaje.CRITICO)), 40)
				.map(a -> a.resultado()).collectList().block(Duration.ofSeconds(30));

		assertThat(resultados).filteredOn(r -> r == ResultadoAplicacion.APLICADO).hasSize(1);
		assertThat(resultados).filteredOn(r -> r == ResultadoAplicacion.DUPLICADO).hasSize(39);
		assertThat(estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void elMismoPacienteRegistradoEnParaleloConEventIdsDistintosSumaUnaSolaVez() {
		UUID paciente = UUID.randomUUID();

		var resultados = Flux.range(0, 40)
				.flatMap(i -> vista.aplicar(UUID.randomUUID(), T0, registrar(paciente, NivelTriaje.URGENTE)), 40)
				.map(a -> a.resultado()).collectList().block(Duration.ofSeconds(30));

		assertThat(resultados).filteredOn(r -> r == ResultadoAplicacion.APLICADO).hasSize(1);
		assertThat(resultados).filteredOn(r -> r == ResultadoAplicacion.IGNORADO_YA_REGISTRADO).hasSize(39);
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);
	}

	@Test
	void altasSimultaneasDelMismoPacienteRestanUnaSolaVez() {
		UUID paciente = UUID.randomUUID();
		vista.aplicar(UUID.randomUUID(), T0, registrar(paciente, NivelTriaje.CRITICO)).block();
		vista.aplicar(UUID.randomUUID(), T0, registrar(UUID.randomUUID(), NivelTriaje.CRITICO)).block();

		Flux.range(0, 30)
				.flatMap(i -> vista.aplicar(UUID.randomUUID(), T0, new OperacionEstado.DarDeAlta(paciente)), 30)
				.blockLast(Duration.ofSeconds(30));

		assertThat(estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
	}

	/**
	 * Una secuencia pseudoaleatoria (con reentregas de eventId, pacientes desconocidos y registros repetidos) da el mismo
	 * resultado paso a paso en Redis (Lua) que en el modelo del dominio: el script no se desvia de las reglas.
	 */
	@Test
	void redisYElModeloDeDominioCoincidenEnUnaSecuenciaLarga() {
		Random azar = new Random(42);
		List<UUID> pacientes = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			pacientes.add(UUID.randomUUID());
		}
		List<Object[]> historial = new ArrayList<>();   // {eventId, ocurridoEn, operacion} para reentregar
		VistaUrgencias modelo = VistaUrgencias.vacia();

		for (int paso = 0; paso < 500; paso++) {
			UUID eventId;
			Instant cuando;
			OperacionEstado op;
			if (!historial.isEmpty() && azar.nextInt(10) == 0) {
				Object[] previo = historial.get(azar.nextInt(historial.size()));
				eventId = (UUID) previo[0];
				cuando = (Instant) previo[1];
				op = (OperacionEstado) previo[2];
			}
			else {
				eventId = UUID.randomUUID();
				cuando = T0.plusSeconds(azar.nextInt(10_000)).plus(azar.nextInt(1000), ChronoUnit.MICROS);
				UUID p = pacientes.get(azar.nextInt(pacientes.size()));
				op = switch (azar.nextInt(4)) {
					case 0 -> registrar(p, NivelTriaje.values()[azar.nextInt(5)]);
					case 1 -> new OperacionEstado.PasarAConsulta(p);
					case 2 -> new OperacionEstado.Escalar(p);
					default -> new OperacionEstado.DarDeAlta(p);
				};
				historial.add(new Object[] {eventId, cuando, op});
			}

			var esperado = modelo.aplicar(eventId, cuando, op);
			modelo = esperado.vista();
			var real = vista.aplicar(eventId, cuando, op).block(Duration.ofSeconds(10));

			assertThat(real.resultado()).as("resultado en el paso %d (%s)", paso, op).isEqualTo(esperado.resultado());
			assertThat(real.vista()).as("vista tras el paso %d", paso).isEqualTo(modelo.estado());
		}
		assertThat(estado()).isEqualTo(modelo.estado());
	}

}
