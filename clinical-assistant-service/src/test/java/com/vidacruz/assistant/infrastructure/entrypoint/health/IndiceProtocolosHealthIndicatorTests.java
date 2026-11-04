package com.vidacruz.assistant.infrastructure.entrypoint.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vidacruz.assistant.domain.model.EstadoIndice;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.ResourceBundleMessageSource;

/** El indicador refleja cada estado del indice con sus cuatro detalles y el texto del catalogo de mensajes. */
class IndiceProtocolosHealthIndicatorTests {

	private static final Instant AHORA = Instant.parse("2026-11-04T15:00:00Z");

	private final EstadoIndicePort puerto = mock(EstadoIndicePort.class);
	private final IndiceProtocolosHealthIndicator indicador = new IndiceProtocolosHealthIndicator(puerto, mensajes());

	private static ResourceBundleMessageSource mensajes() {
		ResourceBundleMessageSource m = new ResourceBundleMessageSource();
		m.setBasename("messages");
		m.setDefaultEncoding("UTF-8");
		m.setFallbackToSystemLocale(false);
		return m;
	}

	private Health con(EstadoIndice estado) {
		when(puerto.actual()).thenReturn(estado);
		return indicador.health().block();
	}

	@Test
	void indexandoEsDownSinErrorNiFecha() {
		Health h = con(EstadoIndice.indexando());

		assertThat(h.getStatus()).isEqualTo(Status.DOWN);
		assertThat(h.getDetails()).containsEntry("estado", "INDEXANDO").containsEntry("fragmentosIndexados", 0)
				.containsEntry("descripcion", "Indexando los protocolos; aun no termino la primera indexacion")
				.doesNotContainKeys("detalleError", "actualizadoEn");
	}

	@Test
	void listoEsUpConFragmentosYFecha() {
		Health h = con(EstadoIndice.listo(new ResultadoSincronizacion(0, 0, 0, 7), AHORA));

		assertThat(h.getStatus()).isEqualTo(Status.UP);
		assertThat(h.getDetails()).containsEntry("estado", "LISTO").containsEntry("fragmentosIndexados", 7)
				.containsEntry("actualizadoEn", AHORA.toString()).containsEntry("descripcion", "Indice de protocolos listo")
				.doesNotContainKey("detalleError");
	}

	@Test
	void fallidoEsDownConErrorYFecha() {
		Health h = con(EstadoIndice.fallido("proveedor caido", AHORA));

		assertThat(h.getStatus()).isEqualTo(Status.DOWN);
		assertThat(h.getDetails()).containsEntry("estado", "FALLIDO").containsEntry("fragmentosIndexados", 0)
				.containsEntry("detalleError", "proveedor caido").containsEntry("actualizadoEn", AHORA.toString())
				.containsKey("descripcion");
	}

}
