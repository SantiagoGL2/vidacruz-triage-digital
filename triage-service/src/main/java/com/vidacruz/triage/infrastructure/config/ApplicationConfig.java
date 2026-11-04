package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.application.event.FabricaEventos;
import com.vidacruz.triage.application.port.in.CambiarEstadoPacienteUseCase;
import com.vidacruz.triage.application.port.in.ConsultarPacienteUseCase;
import com.vidacruz.triage.application.port.in.RegistrarPacienteUseCase;
import com.vidacruz.triage.application.usecase.CambiarEstadoPacienteUseCaseImpl;
import com.vidacruz.triage.application.usecase.ConsultarPacienteUseCaseImpl;
import com.vidacruz.triage.application.usecase.RegistrarPacienteUseCaseImpl;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.domain.port.out.MetricasPort;
import com.vidacruz.triage.domain.port.out.OutboxRepositoryPort;
import com.vidacruz.triage.domain.port.out.PacienteRepositoryPort;
import com.vidacruz.triage.domain.port.out.TransaccionPort;
import com.vidacruz.triage.domain.service.ClasificadorTriaje;
import com.vidacruz.triage.domain.service.PoliticaSla;
import com.vidacruz.triage.domain.service.PoliticaSlaEstandar;
import com.vidacruz.triage.domain.service.QuejasAdministrativas;
import com.vidacruz.triage.domain.service.RegistradorPacientes;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cableado del dominio y los casos de uso (que no llevan anotaciones de Spring). */
@Configuration
public class ApplicationConfig {

	/** UTC y truncado a microsegundos (precision de PostgreSQL): lo respondido por el POST coincide con lo persistido. */
	@Bean
	Clock clock() {
		return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
	}

	@Bean
	PoliticaSla politicaSla(TriageSlaProperties propiedades) {
		return new PoliticaSlaEstandar(propiedades.minutos());
	}

	@Bean
	QuejasAdministrativas quejasAdministrativas(TriageClasificacionProperties propiedades) {
		return new QuejasAdministrativas(propiedades.quejasAdministrativas());
	}

	@Bean
	ClasificadorTriaje clasificadorTriaje(QuejasAdministrativas quejasAdministrativas) {
		return new ClasificadorTriaje(quejasAdministrativas);
	}

	@Bean
	RegistradorPacientes registradorPacientes(ClasificadorTriaje clasificador, PoliticaSla politicaSla) {
		return new RegistradorPacientes(clasificador, politicaSla);
	}

	@Bean
	FabricaEventos fabricaEventos(TriageKafkaProperties kafka) {
		return new FabricaEventos(kafka.eventVersion());
	}

	@Bean
	RegistrarPacienteUseCase registrarPacienteUseCase(RegistradorPacientes registrador, PacienteRepositoryPort pacientes,
			AuditoriaRepositoryPort auditoria, OutboxRepositoryPort outbox, FabricaEventos fabricaEventos,
			TransaccionPort transaccion, MetricasPort metricas, Clock clock) {
		return new RegistrarPacienteUseCaseImpl(registrador, pacientes, auditoria, outbox, fabricaEventos, transaccion,
				metricas, clock);
	}

	@Bean
	CambiarEstadoPacienteUseCase cambiarEstadoPacienteUseCase(PacienteRepositoryPort pacientes,
			AuditoriaRepositoryPort auditoria, OutboxRepositoryPort outbox, FabricaEventos fabricaEventos,
			TransaccionPort transaccion, Clock clock) {
		return new CambiarEstadoPacienteUseCaseImpl(pacientes, auditoria, outbox, fabricaEventos, transaccion, clock);
	}

	@Bean
	ConsultarPacienteUseCase consultarPacienteUseCase(PacienteRepositoryPort pacientes, Clock clock) {
		return new ConsultarPacienteUseCaseImpl(pacientes, clock);
	}

}
