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
import com.vidacruz.triage.application.port.in.EscalarPacientesVencidosUseCase;
import com.vidacruz.triage.application.port.in.NotificarEscalamientoUseCase;
import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.application.port.in.SuscribirAlertasUseCase;
import com.vidacruz.triage.application.usecase.EscalarPacientesVencidosUseCaseImpl;
import com.vidacruz.triage.application.usecase.NotificarEscalamientoUseCaseImpl;
import com.vidacruz.triage.application.usecase.PublicarEventosPendientesUseCaseImpl;
import com.vidacruz.triage.application.usecase.SuscribirAlertasUseCaseImpl;
import com.vidacruz.triage.domain.model.PoliticaReintentoOutbox;
import com.vidacruz.triage.domain.port.out.DeduplicacionEventosPort;
import com.vidacruz.triage.domain.port.out.FlujoAlertasPort;
import com.vidacruz.triage.domain.port.out.NotificadorAlertasPort;
import com.vidacruz.triage.domain.port.out.PublicadorEventosPort;
import com.vidacruz.triage.application.port.in.GenerarReporteOperacionUseCase;
import com.vidacruz.triage.application.usecase.GenerarReporteOperacionUseCaseImpl;
import com.vidacruz.triage.domain.port.out.ReporteOperacionPort;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

	@Bean
	EscalarPacientesVencidosUseCase escalarPacientesVencidosUseCase(PacienteRepositoryPort pacientes,
			AuditoriaRepositoryPort auditoria, OutboxRepositoryPort outbox, FabricaEventos fabricaEventos,
			TransaccionPort transaccion, MetricasPort metricas, Clock clock, TriageSlaProperties sla) {
		return new EscalarPacientesVencidosUseCaseImpl(pacientes, auditoria, outbox, fabricaEventos, transaccion, metricas,
				clock, sla.lote());
	}

	@Bean
	PublicarEventosPendientesUseCase publicarEventosPendientesUseCase(OutboxRepositoryPort outbox,
			PublicadorEventosPort publicador, TransaccionPort transaccion, MetricasPort metricas, Clock clock,
			TriageOutboxProperties propiedades) {
		var politica = new PoliticaReintentoOutbox(propiedades.maxIntentos(), propiedades.backoffInicial(),
				propiedades.backoffMaximo());
		return new PublicarEventosPendientesUseCaseImpl(outbox, publicador, transaccion, metricas, clock,
				propiedades.batchSize(), politica);
	}

	@Bean
	NotificarEscalamientoUseCase notificarEscalamientoUseCase(DeduplicacionEventosPort deduplicacion,
			List<NotificadorAlertasPort> canales) {
		return new NotificarEscalamientoUseCaseImpl(deduplicacion, canales);
	}

	@Bean
	SuscribirAlertasUseCase suscribirAlertasUseCase(FlujoAlertasPort flujo) {
		return new SuscribirAlertasUseCaseImpl(flujo);
	}

	@Bean
	GenerarReporteOperacionUseCase generarReporteOperacionUseCase(ReporteOperacionPort reporte,
			TriageReportesProperties propiedades) {
		return new GenerarReporteOperacionUseCaseImpl(reporte, propiedades.zonaHoraria(), propiedades.rangoMaximoDias());
	}

}
