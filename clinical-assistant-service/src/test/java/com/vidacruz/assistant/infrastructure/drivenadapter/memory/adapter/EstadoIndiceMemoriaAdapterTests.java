package com.vidacruz.assistant.infrastructure.drivenadapter.memory.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.assistant.domain.model.EstadoIndice;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.model.enums.EstadoIndexacion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EstadoIndiceMemoriaAdapterTests {

	private static final Instant AHORA = Instant.parse("2026-11-04T15:00:00Z");

	private final EstadoIndiceMemoriaAdapter adapter =
			new EstadoIndiceMemoriaAdapter(Clock.fixed(AHORA, ZoneOffset.UTC));

	@Test
	void empiezaIndexando() {
		assertThat(adapter.actual()).isEqualTo(EstadoIndice.indexando());
	}

	@Test
	void indexandoAListoYDeVueltaAFallidoYAListo() {
		adapter.marcarListo(new ResultadoSincronizacion(0, 0, 0, 7));
		assertThat(adapter.actual()).isEqualTo(new EstadoIndice(EstadoIndexacion.LISTO, 7, null, AHORA));

		adapter.marcarFallo("redis caido");
		assertThat(adapter.actual()).isEqualTo(new EstadoIndice(EstadoIndexacion.FALLIDO, 0, "redis caido", AHORA));

		adapter.marcarListo(new ResultadoSincronizacion(0, 0, 0, 9));
		assertThat(adapter.actual()).isEqualTo(new EstadoIndice(EstadoIndexacion.LISTO, 9, null, AHORA));
	}

	@Test
	void elFalloLimpiaLosFragmentosDelEstadoAnterior() {
		adapter.marcarListo(new ResultadoSincronizacion(0, 0, 0, 7));
		adapter.marcarFallo("x");

		assertThat(adapter.actual().fragmentosIndexados()).isZero();
	}

	@Test
	void unLectorConcurrenteNuncaVeUnaMezclaDeEstados() throws Exception {
		// Si el estado se guardara en campos sueltos, un lector podria ver LISTO con error, o FALLIDO con fragmentos:
		// el record rechaza esas combinaciones, asi que basta con leer sin excepciones y con coherencia.
		AtomicBoolean seguir = new AtomicBoolean(true);
		CountDownLatch listos = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			var escritor = pool.submit(() -> {
				listos.countDown();
				for (int i = 0; i < 200_000; i++) {
					if (i % 2 == 0) {
						adapter.marcarListo(new ResultadoSincronizacion(0, 0, 0, 7));
					}
					else {
						adapter.marcarFallo("e");
					}
				}
				seguir.set(false);
			});
			var lector = pool.submit(() -> {
				listos.await();
				while (seguir.get()) {
					EstadoIndice e = adapter.actual();
					boolean coherente = switch (e.estado()) {
						case LISTO -> e.fragmentosIndexados() == 7 && e.detalleError() == null;
						case FALLIDO -> e.fragmentosIndexados() == 0 && "e".equals(e.detalleError());
						case INDEXANDO -> e.fragmentosIndexados() == 0 && e.detalleError() == null;
					};
					assertThat(coherente).as(e.toString()).isTrue();
				}
				return null;
			});
			escritor.get();
			lector.get();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(List.of(EstadoIndexacion.LISTO, EstadoIndexacion.FALLIDO)).contains(adapter.actual().estado());
	}

}
