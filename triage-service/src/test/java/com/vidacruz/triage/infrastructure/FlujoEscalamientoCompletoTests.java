package com.vidacruz.triage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.infrastructure.entrypoint.web.dto.response.AlertaResponse;
import com.vidacruz.triage.support.AbstractProcesosTest;
import com.vidacruz.triage.support.Contenedores;
import com.vidacruz.triage.support.RelojMutable;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.test.StepVerifier;

/**
 * Flujo completo con TODOS los procesos de fondo encendidos (escalamiento, publicador del outbox y consumidor de
 * alertas): registrar -> vence el SLA -> se escala -> evento en el outbox -> Kafka -> alerta por SSE.
 * El SLA minimo del dominio es 1 minuto, asi que el "paso del tiempo" lo da el reloj de prueba.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlujoEscalamientoCompletoTests extends AbstractProcesosTest {

	@TestConfiguration
	static class RelojDePrueba {

		@Bean
		@Primary
		RelojMutable relojMutable() {
			return new RelojMutable();
		}

	}

	@DynamicPropertySource
	static void procesosEncendidos(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", () -> Contenedores.kafka().getBootstrapServers());
		registry.add("triage.sla.habilitado", () -> "true");
		registry.add("triage.sla.intervalo-evaluacion", () -> "PT0.5S");
		registry.add("triage.outbox.habilitado", () -> "true");
		registry.add("triage.outbox.intervalo", () -> "PT0.5S");
		registry.add("triage.alertas.consumidor.habilitado", () -> "true");
		registry.add("triage.alertas.consumidor.group-id", () -> "flujo-test-" + UUID.randomUUID());
	}

	@Autowired
	RelojMutable reloj;

	@Test
	void unPacienteConSlaCortoSeEscalaYLaAlertaLlegaPorSse() {
		reloj.fijar(java.time.Instant.now());
		var alertas = api().get().uri("/triage/alertas/stream").accept(MediaType.TEXT_EVENT_STREAM).exchange()
				.expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<AlertaResponse>>() { })
				.getResponseBody()
				.filter(e -> e.data() != null)
				.map(ServerSentEvent::data);

		UUID paciente = registrar(cuerpoCritico(), "corr-flujo-completo");      // SLA 5 min
		StepVerifier.create(alertas.filter(a -> a.pacienteId().equals(paciente)))
				.then(() -> reloj.avanzar(Duration.ofMinutes(6)))                  // "pasan" 6 minutos
				.assertNext(a -> {
					assertThat(a.nivel()).isEqualTo("CRITICO");
					assertThat(a.slaMinutos()).isEqualTo(5);
					assertThat(a.minutosEspera()).isGreaterThanOrEqualTo(5);
					assertThat(a.correlationId()).startsWith("escalamiento-sla-");   // nacio del ciclo de fondo
				})
				.thenCancel()
				.verify(Duration.ofSeconds(60));

		assertThat(paciente(paciente).get("escalado").asBoolean()).isTrue();
		assertThat(contarAuditoria(paciente, "PACIENTE_ESCALADO")).isEqualTo(1);
		assertThat(contarEventos(paciente, "paciente_escalado")).isEqualTo(1);
	}

}
