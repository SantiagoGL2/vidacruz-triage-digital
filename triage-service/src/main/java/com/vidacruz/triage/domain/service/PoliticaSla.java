package com.vidacruz.triage.domain.service;

import com.vidacruz.triage.domain.model.enums.NivelTriaje;

/** SLA en minutos por nivel (RN-02). Infraestructura puede proveer una implementacion desde configuracion. */
public interface PoliticaSla {

	int minutosPara(NivelTriaje nivel);

}
