package com.vidacruz.assistant.infrastructure.entrypoint.kafka;

import static com.vidacruz.assistant.support.EventosTriaje.alta;
import static com.vidacruz.assistant.support.EventosTriaje.enConsulta;
import static com.vidacruz.assistant.support.EventosTriaje.escalado;
import static com.vidacruz.assistant.support.EventosTriaje.registrado;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.Contenedores;
import com.vidacruz.assistant.support.EventosTriaje;
import com.vidacruz.assistant.support.KafkaDePrueba;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.DockerClientFactory;

/**
 * RF-04 parte B de punta a punta: eventos reales por Kafka (Testcontainers) -> listener -> Redis (Testcontainers).
 * La vista se reconstruye solo con lo que llega por el topic.
 */
class ProyeccionKafkaTests extends AbstractIntegrationTest {

	private static final Duration PLAZO = Duration.ofSeconds(30);

	@Value("${assistant.kafka.group-id}")
	String grupo;

	private void esperarTotal(long total) {
		await().atMost(PLAZO).untilAsserted(() -> assertThat(estado().totalActivos()).isEqualTo(total));
	}

	private void esperarContador(double esperado, String nombre, String... tags) {
		await().atMost(PLAZO).untilAsserted(() -> assertThat(contador(nombre, tags)).isEqualTo(esperado));
	}

	// ------------------------------------------------------------------ logica

	@Test
	void secuenciaRegistradoEnConsultaEscaladoYAltaDejaLosContadoresCorrectos() {
		UUID id = UUID.randomUUID();
		KafkaDePrueba.publicar(registrado(id, "URGENTE"));
		esperarTotal(1);
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);

		KafkaDePrueba.publicar(enConsulta(id, "URGENTE"));
		KafkaDePrueba.publicar(escalado(id, "URGENTE"));
		await().atMost(PLAZO).untilAsserted(() -> assertThat(estado().escalados()).isEqualTo(1));
		assertThat(estado().totalActivos()).isEqualTo(1);                  // en consulta y escalado: sigue activo
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);

		KafkaDePrueba.publicar(alta(id, "URGENTE"));
		esperarTotal(0);
		EstadoUrgencias fin = estado();
		assertThat(fin.porNivel().values()).containsOnly(0L);
		assertThat(fin.escalados()).isZero();
		assertThat(fin.ultimoEventoEn()).isNotNull();
	}

	@Test
	void seisPacientesE1aE6DosEnConsultaUnaAltaDanElConteoPorNivelExacto() {
		// E1 CRITICO, E2 URGENTE, E3 URGENTE_2, E4 NO_URGENTE, E5 NO_URGENTE_2, E6 CRITICO (datos de referencia del enunciado)
		String[] niveles = {"CRITICO", "URGENTE", "URGENTE_2", "NO_URGENTE", "NO_URGENTE_2", "CRITICO"};
		UUID[] ids = new UUID[6];
		for (int i = 0; i < 6; i++) {
			ids[i] = UUID.randomUUID();
			KafkaDePrueba.publicar(registrado(ids[i], niveles[i]));
		}
		esperarTotal(6);
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 2L);

		KafkaDePrueba.publicar(enConsulta(ids[0], niveles[0]));   // E1 pasa a consulta
		KafkaDePrueba.publicar(enConsulta(ids[1], niveles[1]));   // E2 pasa a consulta
		KafkaDePrueba.publicar(alta(ids[5], niveles[5]));          // E6 se va
		esperarTotal(5);

		EstadoUrgencias e = estado();
		assertThat(e.porNivel()).containsEntry(NivelTriaje.CRITICO, 1L)
				.containsEntry(NivelTriaje.URGENTE, 1L)
				.containsEntry(NivelTriaje.URGENTE_2, 1L)
				.containsEntry(NivelTriaje.NO_URGENTE, 1L)
				.containsEntry(NivelTriaje.NO_URGENTE_2, 1L);
		assertThat(e.escalados()).isZero();
	}

	@Test
	void elMismoEventoEnviadoDosVecesSeAplicaUnaSolaVez() {
		double duplicadosAntes = contador("asistente.eventos.duplicados");
		var evento = registrado(UUID.randomUUID(), "CRITICO");

		KafkaDePrueba.publicar(evento);
		KafkaDePrueba.publicar(evento);   // mismo eventId, mismo mensaje (reentrega at-least-once)

		esperarContador(duplicadosAntes + 1, "asistente.eventos.duplicados");
		assertThat(estado().totalActivos()).isEqualTo(1);
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
	}

	@Test
	void unRegistroDuplicadoDelMismoPacienteConOtroEventIdNoSumaDosVeces() {
		UUID id = UUID.randomUUID();
		double antes = contador("asistente.eventos.ignorados", "motivo", "ya_registrado");

		KafkaDePrueba.publicar(registrado(id, "URGENTE"));
		KafkaDePrueba.publicar(registrado(id, "URGENTE"));

		esperarContador(antes + 1, "asistente.eventos.ignorados", "motivo", "ya_registrado");
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L);
		assertThat(estado().totalActivos()).isEqualTo(1);
	}

	@Test
	void unaAltaDeUnPacienteDesconocidoSeIgnoraYNoDejaContadoresNegativos() {
		double antes = contador("asistente.eventos.ignorados", "motivo", "paciente_desconocido");

		KafkaDePrueba.publicar(alta(UUID.randomUUID(), "CRITICO"));
		KafkaDePrueba.publicar(enConsulta(UUID.randomUUID(), "CRITICO"));

		esperarContador(antes + 2, "asistente.eventos.ignorados", "motivo", "paciente_desconocido");
		EstadoUrgencias e = estado();
		assertThat(e.porNivel().values()).containsOnly(0L);
		assertThat(e.totalActivos()).isZero();
	}

	// ------------------------------------------------------------------ mensajes malos

	@Test
	void unJsonInvalidoTerminaEnLaDltYElConsumoSigueConElSiguienteMensaje() {
		double dltAntes = contador("asistente.eventos.dlt");
		String clave = "malo-" + UUID.randomUUID();
		String basura = "esto { no es json";
		try (var dlt = new KafkaDePrueba.LectorDlt()) {
			KafkaDePrueba.publicar(clave, basura, null);
			KafkaDePrueba.publicar(registrado(UUID.randomUUID(), "CRITICO"));   // el siguiente mensaje debe procesarse

			esperarTotal(1);
			var enDlt = dlt.esperarClave(clave, Duration.ofSeconds(20));
			assertThat(enDlt.value()).as("la DLT conserva el mensaje original, byte a byte").isEqualTo(basura);
			assertThat(enDlt.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
		}
		esperarContador(dltAntes + 1, "asistente.eventos.dlt");
	}

	@Test
	void unEventoConocidoPeroIncompletoVaDirectoALaDltSinReintentos() {
		double dltAntes = contador("asistente.eventos.dlt");
		String clave = "incompleto-" + UUID.randomUUID();
		String sinPaciente = """
				{"eventId":"%s","eventType":"paciente_registrado","occurredAt":"2026-11-04T15:00:00Z","version":1,
				 "payload":{"nivel":"CRITICO"}}""".formatted(UUID.randomUUID());
		try (var dlt = new KafkaDePrueba.LectorDlt()) {
			KafkaDePrueba.publicar(clave, sinPaciente, null);
			KafkaDePrueba.publicar(registrado(UUID.randomUUID(), "URGENTE"));

			esperarTotal(1);
			assertThat(dlt.esperarClave(clave, Duration.ofSeconds(20)).value()).contains("paciente_registrado");
		}
		esperarContador(dltAntes + 1, "asistente.eventos.dlt");
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.URGENTE, 1L).containsEntry(NivelTriaje.CRITICO, 0L);
	}

	@Test
	void unEventTypeDesconocidoSeIgnoraSinIrALaDlt() {
		double dltAntes = contador("asistente.eventos.dlt");
		double ignoradosAntes = contador("asistente.eventos.ignorados", "motivo", "tipo_desconocido");
		String clave = "futuro-" + UUID.randomUUID();
		String desconocido = """
				{"eventId":"%s","eventType":"paciente_trasladado","occurredAt":"2026-11-04T15:00:00Z","version":2,
				 "campoNuevo":{"a":[1,2,3]},"payload":{"pacienteId":"x","destino":"UCI"}}""".formatted(UUID.randomUUID());
		try (var dlt = new KafkaDePrueba.LectorDlt()) {
			KafkaDePrueba.publicar(clave, desconocido, null);
			KafkaDePrueba.publicar(registrado(UUID.randomUUID(), "CRITICO"));

			esperarContador(ignoradosAntes + 1, "asistente.eventos.ignorados", "motivo", "tipo_desconocido");
			esperarTotal(1);
			assertThat(dlt.leer(Duration.ofSeconds(3))).extracting(r -> r.key()).doesNotContain(clave);
		}
		assertThat(contador("asistente.eventos.dlt")).isEqualTo(dltAntes);
	}

	// ------------------------------------------------------------------ Redis caido

	private static void pausarRedis() {
		dockerClient().pauseContainerCmd(Contenedores.redis().getContainerId()).exec();
	}

	private static void reanudarRedis() {
		try {
			dockerClient().unpauseContainerCmd(Contenedores.redis().getContainerId()).exec();
		}
		catch (Exception e) {
			// ya estaba en marcha
		}
	}

	private static DockerClient dockerClient() {
		return DockerClientFactory.lazyClient();
	}

	@Test
	void conRedisCaidoElMensajeSeReintentaYSeAplicaCuandoRedisVuelve() {
		double dltAntes = contador("asistente.eventos.dlt");
		UUID id = UUID.randomUUID();
		pausarRedis();
		try {
			KafkaDePrueba.publicar(registrado(id, "CRITICO"));

			// El mensaje no se pierde ni se confirma mientras Redis no responde: el lag se mantiene (offset sin confirmar)
			await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
					.until(() -> KafkaDePrueba.lag(grupo) >= 1);
		}
		finally {
			reanudarRedis();
		}

		esperarTotal(1);
		assertThat(estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);   // una sola vez, pese a los reintentos
		await().atMost(PLAZO).until(() -> KafkaDePrueba.lag(grupo) == 0);          // ahora si se confirmo el offset
		assertThat(contador("asistente.eventos.dlt")).isEqualTo(dltAntes);          // no fue a la DLT
	}

	@Test
	void sinRedisElOffsetNoAvanzaYConRedisSeConfirma() {
		// Mismo mecanismo visto desde el offset: el listener devuelve Mono y spring-kafka confirma al completar
		EventosTriaje.Evento evento = registrado(UUID.randomUUID(), "URGENTE_2");
		pausarRedis();
		try {
			KafkaDePrueba.publicar(evento);
			await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8)).until(() -> KafkaDePrueba.lag(grupo) >= 1);
		}
		finally {
			reanudarRedis();
		}
		await().atMost(PLAZO).until(() -> KafkaDePrueba.lag(grupo) == 0);
		assertThat(List.of(estado().porNivel().get(NivelTriaje.URGENTE_2))).containsExactly(1L);
	}

}
