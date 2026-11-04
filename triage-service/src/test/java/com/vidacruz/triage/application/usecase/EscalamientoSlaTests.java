package com.vidacruz.triage.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.vidacruz.triage.application.port.in.EscalarPacientesVencidosUseCase;
import com.vidacruz.triage.application.result.ResultadoEscalamiento;
import com.vidacruz.triage.support.AbstractProcesosTest;
import com.vidacruz.triage.support.RelojMutable;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

/**
 * RF-03 / RN-03 contra PostgreSQL real. El reloj es de prueba: se registra un paciente y se "avanza el tiempo" para
 * vencer el SLA (SLA minimo = 1 minuto, asi que no se puede esperar de verdad en un test).
 */
@TestPropertySource(properties = "triage.sla.lote=1000")
class EscalamientoSlaTests extends AbstractProcesosTest {

	@TestConfiguration
	static class RelojDePrueba {

		@Bean
		@Primary
		RelojMutable relojMutable() {
			return new RelojMutable();
		}

	}

	@Autowired
	EscalarPacientesVencidosUseCase escalar;

	@Autowired
	RelojMutable reloj;

	@Autowired
	MeterRegistry meterRegistry;

	@BeforeEach
	void relojAhora() {
		reloj.fijar(java.time.Instant.now());
	}

	/** Ejecuta ciclos hasta que no queden candidatos (puede haber pacientes vencidos de otras clases de test). */
	private List<ResultadoEscalamiento> drenar() {
		List<ResultadoEscalamiento> ciclos = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			ResultadoEscalamiento r = escalar.escalarVencidos().block(Duration.ofSeconds(60));
			ciclos.add(r);
			if (r.candidatos() == 0) {
				break;
			}
		}
		return ciclos;
	}

	@Test
	void unPacienteConSlaVencidoSeEscalaConUnaAuditoriaYUnEvento() {
		var contador = meterRegistry.counter("triage.pacientes.escalados", "nivel", "CRITICO");
		double antes = contador.count();
		UUID id = registrar(cuerpoCritico());          // SLA 5 min
		reloj.avanzar(Duration.ofMinutes(6));

		drenar();

		JsonNode p = paciente(id);
		assertThat(p.get("escalado").asBoolean()).isTrue();
		assertThat(p.get("estado").asText()).isEqualTo("REGISTRADO");     // el escalamiento no cambia el estado
		assertThat(p.get("fechaEscalamiento").isNull()).isFalse();
		assertThat(contarAuditoria(id, "PACIENTE_ESCALADO")).isEqualTo(1);
		assertThat(contarEventos(id, "paciente_escalado")).isEqualTo(1);
		JsonNode evento = outbox(id).stream().filter(e -> e.get("eventType").asText().equals("paciente_escalado"))
				.findFirst().orElseThrow();
		assertThat(evento.get("payload").get("estadoAnterior").asText()).isEqualTo("REGISTRADO");
		assertThat(evento.get("payload").get("estadoNuevo").asText()).isEqualTo("REGISTRADO");
		assertThat(evento.get("payload").get("escalado").asBoolean()).isTrue();
		assertThat(evento.get("payload").get("slaMinutos").asInt()).isEqualTo(5);
		assertThat(contador.count()).isEqualTo(antes + 1);
	}

	@Test
	void ejecutarElEscalamientoDosVecesNoDuplicaNada() {
		UUID id = registrar(cuerpoCritico());
		reloj.avanzar(Duration.ofMinutes(6));
		drenar();

		var segunda = drenar();

		assertThat(segunda).allSatisfy(r -> assertThat(r.escalados()).isZero());
		assertThat(contarAuditoria(id, "PACIENTE_ESCALADO")).isEqualTo(1);
		assertThat(contarEventos(id, "paciente_escalado")).isEqualTo(1);
	}

	@Test
	void ejecucionesConcurrentesEscalanCadaPacienteUnaSolaVez() {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			ids.add(registrar(cuerpoCritico()));
		}
		reloj.avanzar(Duration.ofMinutes(6));

		// cuatro ciclos simultaneos sobre los mismos candidatos (como cuatro replicas)
		Mono.zip(escalar.escalarVencidos(), escalar.escalarVencidos(), escalar.escalarVencidos(), escalar.escalarVencidos())
				.block(Duration.ofSeconds(120));
		drenar();

		for (UUID id : ids) {
			assertThat(paciente(id).get("escalado").asBoolean()).as("paciente %s escalado", id).isTrue();
			assertThat(contarAuditoria(id, "PACIENTE_ESCALADO")).as("auditorias de %s", id).isEqualTo(1);
			assertThat(contarEventos(id, "paciente_escalado")).as("eventos de %s", id).isEqualTo(1);
		}
	}

	@Test
	void enConsultaAltaYDentroDelSlaNuncaSeEscalan() {
		UUID vencido = registrar(cuerpoCritico());             // control: este si debe escalarse
		UUID enConsulta = registrar(cuerpoCritico());
		cambiarEstado(enConsulta, "EN_CONSULTA").expectStatus().isOk();
		UUID dadoDeAlta = registrar(cuerpoCritico());
		cambiarEstado(dadoDeAlta, "ALTA").expectStatus().isOk();
		reloj.avanzar(Duration.ofMinutes(4));
		UUID dentroDelSla = registrar(cuerpoCritico());        // registrado hace 0 min en t+4
		reloj.avanzar(Duration.ofMinutes(2));                  // t+6: los tres primeros pasaron 6 min; este solo 2

		drenar();

		assertThat(paciente(vencido).get("escalado").asBoolean()).isTrue();
		for (UUID id : List.of(enConsulta, dadoDeAlta, dentroDelSla)) {
			assertThat(paciente(id).get("escalado").asBoolean()).as("paciente %s no debe escalarse", id).isFalse();
			assertThat(contarAuditoria(id, "PACIENTE_ESCALADO")).isZero();
			assertThat(contarEventos(id, "paciente_escalado")).isZero();
		}
	}

	@Test
	void elLimiteDelSlaEsEstricto() {
		UUID id = registrar(cuerpoCritico());                   // SLA 5 min
		reloj.avanzar(Duration.ofMinutes(5));                   // justo en el vencimiento: todavia cumple

		drenar();
		assertThat(paciente(id).get("escalado").asBoolean()).isFalse();

		reloj.avanzar(Duration.ofSeconds(1));
		drenar();
		assertThat(paciente(id).get("escalado").asBoolean()).isTrue();
	}

	@Test
	void elFlagDeEscalamientoPersisteAlCambiarDeEstado() {
		// El escalamiento sube la version, asi que el PATCH lee el paciente ya escalado y conserva el flag historico.
		UUID id = registrar(cuerpoCritico());
		reloj.avanzar(Duration.ofMinutes(6));
		drenar();

		cambiarEstado(id, "EN_CONSULTA").expectStatus().isOk();

		JsonNode p = paciente(id);
		assertThat(p.get("estado").asText()).isEqualTo("EN_CONSULTA");
		assertThat(p.get("escalado").asBoolean()).as("el flag es historico y persiste (supuesto 13)").isTrue();
	}

}
