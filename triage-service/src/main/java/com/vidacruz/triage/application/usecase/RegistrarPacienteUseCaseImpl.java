package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.command.RegistrarPacienteComando;
import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.application.port.in.RegistrarPacienteUseCase;
import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.model.DatosRegistro;
import com.vidacruz.triage.domain.model.Paciente;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import com.vidacruz.triage.domain.service.RegistradorPacientes;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** RF-01. Nunca se loguea el nombre ni la queja del paciente: solo id, nivel y SLA. */
@Slf4j
public class RegistrarPacienteUseCaseImpl implements RegistrarPacienteUseCase {

	private final RegistradorPacientes registrador;
	private final PacienteRepositoryPort pacientes;
	private final RegistroCambioClinico registroCambio;
	private final TransaccionPort transaccion;
	private final MetricasPort metricas;
	private final Clock clock;

	public RegistrarPacienteUseCaseImpl(RegistradorPacientes registrador, PacienteRepositoryPort pacientes,
			AuditoriaRepositoryPort auditoria, OutboxRepositoryPort outbox, FabricaEventos fabricaEventos,
			TransaccionPort transaccion, MetricasPort metricas, Clock clock) {
		this.registrador = registrador;
		this.pacientes = pacientes;
		this.registroCambio = new RegistroCambioClinico(auditoria, outbox, fabricaEventos);
		this.transaccion = transaccion;
		this.metricas = metricas;
		this.clock = clock;
	}

	@Override
	public Mono<Paciente> registrar(RegistrarPacienteComando comando) {
		return Mono.fromSupplier(() -> registrarEnDominio(comando))
				.flatMap(cambio -> transaccion.enTransaccion(
						pacientes.insertar(cambio.paciente())
								.then(Mono.defer(() -> registroCambio.registrar(cambio)))
								.thenReturn(cambio.paciente())))
				.doOnSuccess(p -> {
					// Solo tras el commit: si la transaccion hizo rollback no se cuenta nada.
					metricas.pacienteClasificado(p.nivel());
					log.info("Paciente registrado id={} nivel={} slaMinutos={}", p.id(), p.nivel(), p.slaMinutos());
				});
	}

	/** Solo traduce el comando a datos de dominio: validar, clasificar y asignar SLA es del dominio. */
	private CambioClinico registrarEnDominio(RegistrarPacienteComando c) {
		var datos = new DatosRegistro(c.nombreCompleto(), c.edad(), c.frecuenciaCardiaca(), c.presionSistolica(),
				c.presionDiastolica(), c.spo2(), c.temperatura(), c.dolor(), c.gcs(), c.quejaPrincipal());
		return registrador.registrar(UUID.randomUUID(), datos, clock.instant());
	}

}
