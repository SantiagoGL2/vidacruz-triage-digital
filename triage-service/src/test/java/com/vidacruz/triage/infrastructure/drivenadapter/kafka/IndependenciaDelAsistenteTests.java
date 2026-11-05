package com.vidacruz.triage.infrastructure.drivenadapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.support.AbstractProcesosTest;
import com.vidacruz.triage.support.ConsumidorDePrueba;
import com.vidacruz.triage.support.KafkaDePrueba;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * El triaje no depende del asistente (enunciado). Aqui el asistente NO existe: ni en el classpath ni como consumidor en el
 * broker. Aun asi se registra, se cambia de estado, se audita y los eventos llegan a Kafka. La regla de ArchUnit
 * ({@code ArquitecturaTests.triageNoDependeDelAsistente}) garantiza lo mismo a nivel de codigo.
 */
class IndependenciaDelAsistenteTests extends AbstractProcesosTest {

	static final KafkaDePrueba KAFKA = new KafkaDePrueba();

	@DynamicPropertySource
	static void kafkaPropio(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
	}

	@Autowired
	PublicarEventosPendientesUseCase publicar;

	@Test
	void elAsistenteNoEstaEnElClasspathDeTriage() {
		assertThatThrownBy(() -> Class.forName("com.vidacruz.assistant.ClinicalAssistantApplication"))
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void sinElAsistenteSeRegistraSeCambiaDeEstadoSeAuditaYLosEventosLlegaranAKafka() throws Exception {
		UUID a = registrar(cuerpoNormal());
		UUID b = registrar(cuerpoCritico());
		UUID c = registrar(cuerpoNormal());
		cambiarEstado(a, "EN_CONSULTA").expectStatus().isOk();
		cambiarEstado(b, "ALTA").expectStatus().isOk();                    // alta directa
		cambiarEstado(a, "ALTA").expectStatus().isOk();

		// Todo quedo auditado y en el outbox, sin que nadie del lado del asistente participe.
		assertThat(contarAuditoria(a, "PACIENTE_REGISTRADO")).isEqualTo(1);
		assertThat(contarAuditoria(a, "PACIENTE_ALTA")).isEqualTo(1);
		assertThat(contarEventos(c, "paciente_registrado")).isEqualTo(1);
		assertThat(paciente(c).get("estado").asText()).isEqualTo("REGISTRADO");

		// Los eventos salen al topic aunque no haya ningun consumidor del asistente.
		Set<UUID> ids = Set.of(a, b, c);
		await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(300)).until(() -> {
			publicar.publicarPendientes().block(Duration.ofSeconds(60));
			return ids.stream().allMatch(id -> outbox(id).stream().allMatch(e -> e.get("_publicado").asBoolean()));
		});
		try (var consumidor = new ConsumidorDePrueba(KAFKA.bootstrapServers(), "triaje.eventos")) {
			var registros = consumidor.leerHasta(Duration.ofSeconds(30),
					leidos -> leidos.stream().filter(r -> ids.contains(UUID.fromString(r.key()))).count() >= 6);
			assertThat(registros.stream().filter(r -> ids.contains(UUID.fromString(r.key()))).count()).isGreaterThanOrEqualTo(6);
		}

		// Y en el broker no existe ningun grupo de consumidores del asistente.
		try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.bootstrapServers()))) {
			List<String> grupos = admin.listConsumerGroups().all().get().stream().map(ConsumerGroupListing::groupId)
					.collect(Collectors.toList());
			assertThat(grupos).noneMatch(g -> g.contains("asistente"));
		}
	}

}
