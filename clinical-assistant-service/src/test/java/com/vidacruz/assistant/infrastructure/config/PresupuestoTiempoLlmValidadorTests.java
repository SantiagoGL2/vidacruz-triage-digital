package com.vidacruz.assistant.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** El read-timeout HTTP debe ser menor que el TimeLimiter; si no, la aplicacion no arranca. */
class PresupuestoTiempoLlmValidadorTests {

	@Test
	void aceptaUnReadTimeoutMenorQueElTimeLimiter() {
		assertThatCode(() -> PresupuestoTiempoLlmValidador.validar(Duration.ofSeconds(8), Duration.ofSeconds(10))).doesNotThrowAnyException();
	}

	@Test
	void rechazaUnReadTimeoutIgualOMayorQueElTimeLimiter() {
		assertThatThrownBy(() -> PresupuestoTiempoLlmValidador.validar(Duration.ofSeconds(10), Duration.ofSeconds(10)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("LLM_HTTP_READ_TIMEOUT").hasMessageContaining("LLM_TIMEOUT");
		assertThatThrownBy(() -> PresupuestoTiempoLlmValidador.validar(Duration.ofSeconds(30), Duration.ofSeconds(20)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rechazaLaFaltaDeReadTimeout() {
		assertThatThrownBy(() -> PresupuestoTiempoLlmValidador.validar(null, Duration.ofSeconds(10)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("falta");
		assertThatThrownBy(() -> PresupuestoTiempoLlmValidador.validar(Duration.ZERO, Duration.ofSeconds(10)))
				.isInstanceOf(IllegalStateException.class);
	}

}
