package com.vidacruz.triage.infrastructure.drivenadapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.vidacruz.triage.domain.event.EventoPendiente;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import com.vidacruz.triage.support.AbstractProcesosTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * FOR UPDATE SKIP LOCKED dentro de la transaccion reactiva: dos "replicas" reservan a la vez y mantienen sus bloqueos
 * durante un segundo. La segunda no debe esperar ni recibir lo que la primera tiene reservado.
 */
class OutboxConcurrenciaTests extends AbstractProcesosTest {

	@Autowired
	OutboxRepositoryPort outbox;

	@Autowired
	TransaccionPort transaccion;

	private Mono<List<UUID>> reservarYRetener(Instant ahora) {
		var politica = new PoliticaReintentoOutbox(5, Duration.ofSeconds(1), Duration.ofSeconds(10));
		return transaccion.enTransaccion(outbox.reservarPendientes(5, ahora, politica)
				.map(EventoPendiente::eventId).collectList()
				.delayUntil(ids -> Mono.delay(Duration.ofSeconds(1))))
				.subscribeOn(Schedulers.parallel());
	}

	@Test
	void dosReservasSimultaneasNuncaTomanElMismoEvento() {
		for (int i = 0; i < 10; i++) {
			registrar(cuerpoCritico());      // un evento paciente_registrado por paciente (agregados distintos)
		}
		Instant ahora = Instant.now();

		var resultado = Mono.zip(reservarYRetener(ahora), reservarYRetener(ahora)).block(Duration.ofSeconds(60));

		List<UUID> a = resultado.getT1();
		List<UUID> b = resultado.getT2();
		assertThat(a).hasSize(5);
		assertThat(b).hasSize(5);
		Set<UUID> union = new HashSet<>(a);
		union.addAll(new ArrayList<>(b));
		assertThat(union).as("ningun evento reservado por las dos a la vez").hasSize(10);
	}

}
