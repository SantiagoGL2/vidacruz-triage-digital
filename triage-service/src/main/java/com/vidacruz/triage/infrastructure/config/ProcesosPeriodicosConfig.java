package com.vidacruz.triage.infrastructure.config;

import com.vidacruz.triage.application.port.in.EscalarPacientesVencidosUseCase;
import com.vidacruz.triage.application.port.in.PublicarEventosPendientesUseCase;
import com.vidacruz.triage.infrastructure.entrypoint.scheduler.CicloPeriodico;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Procesos de fondo: escalamiento por SLA (RF-03) y publicador del outbox. Cada uno se apaga con su propiedad. */
@Configuration
class ProcesosPeriodicosConfig {

	@Bean
	@ConditionalOnProperty(name = "triage.sla.habilitado", havingValue = "true", matchIfMissing = true)
	CicloPeriodico cicloEscalamiento(EscalarPacientesVencidosUseCase escalar, TriageSlaProperties sla) {
		return new CicloPeriodico("escalamiento-sla", sla.intervaloEvaluacion(), escalar::escalarVencidos);
	}

	@Bean
	@ConditionalOnProperty(name = "triage.outbox.habilitado", havingValue = "true", matchIfMissing = true)
	CicloPeriodico cicloOutbox(PublicarEventosPendientesUseCase publicar, TriageOutboxProperties outbox) {
		return new CicloPeriodico("outbox", outbox.intervalo(), publicar::publicarPendientes);
	}

}
