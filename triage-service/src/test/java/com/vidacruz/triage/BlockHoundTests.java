package com.vidacruz.triage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vidacruz.triage.support.BlockHoundSupport;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Comprueba que BlockHound esta realmente activo: sin este test un fallo de instalacion pasaria desapercibido. */
class BlockHoundTests {

	@BeforeAll
	static void instalar() {
		BlockHoundSupport.instalar();
	}

	@Test
	void detectaUnSleepEnUnHiloNoBloqueante() {
		Mono<Long> bloqueante = Mono.delay(Duration.ofMillis(1)).doOnNext(x -> {
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		assertThatThrownBy(() -> bloqueante.block(Duration.ofSeconds(5)))
				.hasCauseInstanceOf(BlockingOperationError.class);
	}

	@Test
	void permiteBloquearEnBoundedElastic() {
		Mono<String> envuelto = Mono.fromCallable(() -> {
			Thread.sleep(10);
			return "ok";
		}).subscribeOn(Schedulers.boundedElastic());

		org.assertj.core.api.Assertions.assertThat(envuelto.block(Duration.ofSeconds(5))).isEqualTo("ok");
	}

}
