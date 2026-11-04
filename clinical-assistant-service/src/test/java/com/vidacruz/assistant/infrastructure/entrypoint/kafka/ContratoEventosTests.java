package com.vidacruz.assistant.infrastructure.entrypoint.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.infrastructure.entrypoint.kafka.dto.EventoTriajeMensaje;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import com.vidacruz.assistant.support.KafkaDePrueba;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Contrato con triage-service: los JSON de src/test/resources/eventos siguen el formato documentado en
 * docs/eventos.md (envelope + payload). Si triage cambia el contrato, este test es el que avisa.
 */
class ContratoEventosTests extends AbstractIntegrationTest {

	private static final UUID PACIENTE = UUID.fromString("e62f5bb0-848c-4343-9887-19cc8fcf3a0a");
	private static final String[] CICLO = {"paciente_registrado", "paciente_en_consulta", "paciente_escalado", "paciente_alta"};

	private static String leer(String tipo) throws IOException {
		return new String(new ClassPathResource("eventos/" + tipo + ".json").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
	}

	@Test
	void elEnvelopeDocumentadoSeDeserializaConTodosSusCampos() throws IOException {
		var mapper = JsonMapper.builder().findAndAddModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

		EventoTriajeMensaje e = mapper.readValue(leer("paciente_registrado"), EventoTriajeMensaje.class);

		assertThat(e.eventId()).isEqualTo(UUID.fromString("5b0f3c1e-8f6d-4f55-9a0e-2d2f4b5f0a11"));
		assertThat(e.eventType()).isEqualTo("paciente_registrado");
		assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-11-04T15:17:57.285937Z"));
		assertThat(e.version()).isEqualTo(1);
		assertThat(e.correlationId()).isEqualTo("demo-001");
		assertThat(e.payload().get("pacienteId").asText()).isEqualTo(PACIENTE.toString());
		assertThat(e.payload().get("nivel").asText()).isEqualTo("CRITICO");
		assertThat(e.payload().get("fechaRegistro").asText()).isEqualTo("2026-11-04T15:17:57.285937Z");
	}

	@Test
	void loscuatroEventosDocumentadosSeAplicanEnOrdenYDejanLaVistaVacia() throws IOException {
		KafkaDePrueba.publicar(PACIENTE.toString(), leer(CICLO[0]), "demo-001");
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(estado().totalActivos()).isEqualTo(1);
			assertThat(estado().porNivel()).containsEntry(NivelTriaje.CRITICO, 1L);
		});

		KafkaDePrueba.publicar(PACIENTE.toString(), leer(CICLO[1]), "demo-002");
		KafkaDePrueba.publicar(PACIENTE.toString(), leer(CICLO[2]), "escalamiento-sla-dc1f9056");
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
			assertThat(estado().escalados()).isEqualTo(1);
			assertThat(estado().totalActivos()).isEqualTo(1);       // en consulta y escalado: sigue activo
		});

		KafkaDePrueba.publicar(PACIENTE.toString(), leer(CICLO[3]), "demo-003");
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(estado().totalActivos()).isZero());

		var fin = estado();
		assertThat(fin.escalados()).isZero();
		assertThat(fin.porNivel().values()).containsOnly(0L);
		// el ultimo evento es el occurredAt mas reciente de los cuatro (la alta), con precision de microsegundos
		assertThat(fin.ultimoEventoEn()).isEqualTo(Instant.parse("2026-11-04T15:40:00.000001Z"));
	}

	@Test
	void camposNuevosEnElEnvelopeOElPayloadNoRompenElConsumo() throws IOException {
		String conExtras = leer("paciente_registrado")
				.replace("\"version\": 1,", "\"version\": 1, \"origen\": \"triage-v2\",")
				.replace("\"slaMinutos\": 5,", "\"slaMinutos\": 5, \"campoFuturo\": {\"x\": [1, 2]},");

		KafkaDePrueba.publicar(PACIENTE.toString(), conExtras, null);

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(estado().totalActivos()).isEqualTo(1));
	}

}
