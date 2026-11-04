package com.vidacruz.triage.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Si falla un paso (aqui: la escritura del outbox, simulada), no debe quedar nada de los pasos anteriores.
 * Clase aparte porque el spy crea su propio contexto de Spring.
 */
class AtomicidadTransaccionalTests extends AbstractIntegrationTest {

	@MockitoSpyBean
	OutboxRepositoryPort outbox;

	/** Cuerpo valido de registro (nivel 4) con el nombre dado. */
	static Map<String, Object> cuerpo(String nombre) {
		return Map.of("nombreCompleto", nombre, "edad", 40, "frecuenciaCardiaca", 72, "presionSistolica", 120,
				"presionDiastolica", 80, "spo2", 99, "temperatura", 36.5, "dolor", 2, "gcs", 15,
				"quejaPrincipal", "Herida por cortar");
	}

	private void outboxFalla() {
		doReturn(Mono.error(new IllegalStateException("fallo simulado del outbox con detalle interno")))
				.when(outbox).guardar(any());
	}

	@Test
	void siFallaElOutboxAlRegistrarNoQuedaPacienteNiAuditoria() {
		String nombre = "Atomicidad " + UUID.randomUUID();
		long pacientesAntes = contar("pacientes");
		long auditoriaAntes = contar("auditoria_clinica");
		long outboxAntes = contar("outbox_eventos");
		outboxFalla();

		api().post().uri("/triage/pacientes").bodyValue(cuerpo(nombre)).exchange()
				.expectStatus().isEqualTo(500)
				.expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
				.expectBody()
				.jsonPath("$.status").isEqualTo(500)
				.jsonPath("$.detail").value(d -> assertThat((String) d).doesNotContain("simulado").doesNotContain("interno detalle"))
				.jsonPath("$.stackTrace").doesNotExist()
				.jsonPath("$.trace").doesNotExist()
				.jsonPath("$.correlationId").isNotEmpty();

		assertThat(contar("pacientes")).isEqualTo(pacientesAntes);
		assertThat(contar("auditoria_clinica")).isEqualTo(auditoriaAntes);
		assertThat(contar("outbox_eventos")).isEqualTo(outboxAntes);
		Long conEseNombre = db.sql("SELECT count(*) AS n FROM pacientes WHERE nombre_completo = :n").bind("n", nombre)
				.map(f -> f.get("n", Long.class)).one().block();
		assertThat(conEseNombre).isZero();
	}

	@Test
	void siFallaElOutboxAlCambiarEstadoElPacienteSigueEnSuEstadoYSinFilasNuevas() {
		Map<String, Object> creado = api().post().uri("/triage/pacientes").bodyValue(cuerpo("Atomicidad " + UUID.randomUUID()))
				.exchange().expectStatus().isCreated()
				.expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult().getResponseBody();
		UUID id = UUID.fromString((String) creado.get("id"));
		outboxFalla();

		api().patch().uri("/triage/pacientes/{id}/estado", id).bodyValue(Map.of("estado", "EN_CONSULTA")).exchange()
				.expectStatus().isEqualTo(500);

		api().get().uri("/triage/pacientes/{id}", id).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.estado").isEqualTo("REGISTRADO")
				.jsonPath("$.fechaPrimeraConsulta").value(v -> assertThat(v).isNull());
		assertThat(tiposAuditoria(id)).containsExactly("PACIENTE_REGISTRADO");
		assertThat(tiposOutbox(id)).containsExactly("paciente_registrado");
	}

}
