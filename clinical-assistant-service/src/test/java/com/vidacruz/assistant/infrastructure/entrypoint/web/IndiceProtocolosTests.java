package com.vidacruz.assistant.infrastructure.entrypoint.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Mientras el indice no esta listo la consulta responde 503; el indicador de salud lo refleja. */
class IndiceProtocolosTests extends AbstractIntegrationTest {

	@Test
	void conElIndiceNoListoLaConsultaEs503YLaReadinessEstaEnDown() {
		estadoIndice.marcarFallo("prueba: indice no listo");
		try {
			api().post().uri("/asistente/consulta").contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("pregunta", "¿Cuáles son las medidas iniciales ante una crisis hipertensiva?")).exchange()
					.expectStatus().isEqualTo(503).expectBody()
					.jsonPath("$.type").isEqualTo("https://vidacruz.example/errores/indice-no-disponible")
					.jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("falló"));
			anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isEqualTo(503);
			anonimo().get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
			api().get().uri("/actuator/health").exchange().expectStatus().isEqualTo(503).expectBody()
					.jsonPath("$.components.indiceProtocolos.status").isEqualTo("DOWN")
					.jsonPath("$.components.indiceProtocolos.details.estado").isEqualTo("FALLIDO")
					.jsonPath("$.components.indiceProtocolos.details.fragmentosIndexados").isEqualTo(0)
					.jsonPath("$.components.indiceProtocolos.details.detalleError").isEqualTo("prueba: indice no listo")
					.jsonPath("$.components.indiceProtocolos.details.actualizadoEn").isNotEmpty();
		}
		finally {
			estadoIndice.marcarListo(new ResultadoSincronizacion(0, 0, 0, 7));
		}
		anonimo().get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
		assertThat(estadoIndice.actual().listo()).isTrue();
		api().get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.components.indiceProtocolos.details.estado").isEqualTo("LISTO")
				.jsonPath("$.components.indiceProtocolos.details.fragmentosIndexados").isEqualTo(7)
				.jsonPath("$.components.indiceProtocolos.details.detalleError").doesNotExist();
	}

}
