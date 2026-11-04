package com.vidacruz.triage.application.usecase;

import com.vidacruz.triage.application.port.in.SuscribirAlertasUseCase;
import com.vidacruz.triage.domain.model.Alerta;
import com.vidacruz.triage.domain.port.out.FlujoAlertasPort;
import reactor.core.publisher.Flux;

public class SuscribirAlertasUseCaseImpl implements SuscribirAlertasUseCase {

	private final FlujoAlertasPort flujo;

	public SuscribirAlertasUseCaseImpl(FlujoAlertasPort flujo) {
		this.flujo = flujo;
	}

	@Override
	public Flux<Alerta> suscribir() {
		return flujo.alertas();
	}

}
