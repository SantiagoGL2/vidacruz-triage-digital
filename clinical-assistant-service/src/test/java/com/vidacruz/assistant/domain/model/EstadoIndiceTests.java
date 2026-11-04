package com.vidacruz.assistant.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EstadoIndiceTests {

	private static final Instant AHORA = Instant.parse("2026-11-04T15:00:00Z");

	@Test
	void elEstadoInicialEstaIndexandoYSinFechaNiError() {
		EstadoIndice e = EstadoIndice.indexando();

		assertThat(e.estado()).isEqualTo(EstadoIndexacion.INDEXANDO);
		assertThat(e.listo()).isFalse();
		assertThat(e.fragmentosIndexados()).isZero();
		assertThat(e.detalleError()).isNull();
		assertThat(e.actualizadoEn()).isNull();
	}

	@Test
	void listoTraLosFragmentosYLaFechaYNoTieneError() {
		EstadoIndice e = EstadoIndice.listo(new ResultadoSincronizacion(1, 2, 0, 4), AHORA);

		assertThat(e.estado()).isEqualTo(EstadoIndexacion.LISTO);
		assertThat(e.listo()).isTrue();
		assertThat(e.fragmentosIndexados()).isEqualTo(7);
		assertThat(e.detalleError()).isNull();
		assertThat(e.actualizadoEn()).isEqualTo(AHORA);
	}

	@Test
	void fallidoTraeElErrorYNingunFragmento() {
		EstadoIndice e = EstadoIndice.fallido("proveedor caido", AHORA);

		assertThat(e.estado()).isEqualTo(EstadoIndexacion.FALLIDO);
		assertThat(e.listo()).isFalse();
		assertThat(e.fragmentosIndexados()).isZero();
		assertThat(e.detalleError()).isEqualTo("proveedor caido");
		assertThat(e.actualizadoEn()).isEqualTo(AHORA);
	}

	@Test
	void noPermiteCombinacionesIncoherentes() {
		assertThatThrownBy(() -> new EstadoIndice(EstadoIndexacion.LISTO, 3, "error", AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EstadoIndice(EstadoIndexacion.INDEXANDO, 3, null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EstadoIndice(EstadoIndexacion.LISTO, -1, null, AHORA))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EstadoIndice(null, 0, null, AHORA)).isInstanceOf(NullPointerException.class);
	}

}
