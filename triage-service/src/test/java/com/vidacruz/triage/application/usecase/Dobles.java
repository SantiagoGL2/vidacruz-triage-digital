package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.domain.Signos;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.model.enums.EstadoPaciente;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import com.vidacruz.triage.domain.service.ClasificadorTriaje;
import com.vidacruz.triage.domain.service.PoliticaSlaEstandar;
import com.vidacruz.triage.domain.service.RegistradorPacientes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;

/** Utilidades compartidas por los tests unitarios de los casos de uso. */
final class Dobles {

	static final Instant AHORA = Instant.parse("2026-05-01T12:00:00Z");
	static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

	private Dobles() {
	}

	/** Transaccion de mentira: deja pasar el flujo y cuenta cuantas veces se uso. */
	static final class TransaccionFalsa implements TransaccionPort {

		final AtomicInteger usos = new AtomicInteger();

		@Override
		public <T> Mono<T> enTransaccion(Mono<T> operacion) {
			usos.incrementAndGet();
			return operacion;
		}

	}

	static RegistradorPacientes registrador() {
		return new RegistradorPacientes(new ClasificadorTriaje(), new PoliticaSlaEstandar());
	}

	/** Paciente normal (nivel 4) en el estado indicado, registrado 10 minutos antes de AHORA. */
	static Paciente paciente(EstadoPaciente estado) {
		Instant registro = AHORA.minusSeconds(600);
		Paciente inicial = registrador().registrar(UUID.randomUUID(),
				Signos.normales().datos("Paciente Prueba", 40, "Herida por cortar"), registro).paciente();
		return switch (estado) {
			case REGISTRADO -> inicial;
			case EN_CONSULTA -> inicial.cambiarEstado(EstadoPaciente.EN_CONSULTA, registro.plusSeconds(60)).paciente();
			case ALTA -> inicial.cambiarEstado(EstadoPaciente.ALTA, registro.plusSeconds(60)).paciente();
		};
	}

}
