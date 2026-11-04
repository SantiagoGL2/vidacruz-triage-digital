package com.vidacruz.triage.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Reloj de test que se mueve a mano. Permite "esperar" que venza un SLA (minimo 1 minuto en el dominio) sin esperarlo:
 * se registra el paciente y se avanza el reloj. Trunca a microsegundos como el Clock real de la aplicacion.
 */
public final class RelojMutable extends Clock {

	private volatile Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);

	public void fijar(Instant instante) {
		this.ahora = instante.truncatedTo(ChronoUnit.MICROS);
	}

	public void avanzar(Duration duracion) {
		this.ahora = ahora.plus(duracion);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return ahora;
	}

}
