package com.vidacruz.assistant.infrastructure.config;

import com.vidacruz.assistant.application.port.in.AplicarEventoClinicoUseCase;
import com.vidacruz.assistant.application.port.in.ConsultarEstadoUrgenciasUseCase;
import com.vidacruz.assistant.application.usecase.AplicarEventoClinicoUseCaseImpl;
import com.vidacruz.assistant.application.usecase.ConsultarEstadoUrgenciasUseCaseImpl;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import com.vidacruz.assistant.domain.port.out.MetricasPort;
import com.vidacruz.assistant.domain.service.ProyectorUrgencias;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cableado del dominio y los casos de uso (que no llevan anotaciones de Spring). */
@Configuration
public class ApplicationConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	ProyectorUrgencias proyectorUrgencias() {
		return new ProyectorUrgencias();
	}

	@Bean
	AplicarEventoClinicoUseCase aplicarEventoClinicoUseCase(ProyectorUrgencias proyector, EstadoUrgenciasPort estado,
			MetricasPort metricas) {
		return new AplicarEventoClinicoUseCaseImpl(proyector, estado, metricas);
	}

	@Bean
	ConsultarEstadoUrgenciasUseCase consultarEstadoUrgenciasUseCase(EstadoUrgenciasPort estado, MetricasPort metricas,
			Clock clock) {
		return new ConsultarEstadoUrgenciasUseCaseImpl(estado, metricas, clock);
	}

}
