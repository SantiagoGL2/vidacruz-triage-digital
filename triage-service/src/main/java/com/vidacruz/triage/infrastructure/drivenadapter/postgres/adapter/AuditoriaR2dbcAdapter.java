package com.vidacruz.triage.infrastructure.drivenadapter.postgres.adapter;

import com.vidacruz.triage.domain.model.CambioClinico;
import com.vidacruz.triage.domain.port.out.AuditoriaRepositoryPort;
import com.vidacruz.triage.infrastructure.drivenadapter.postgres.mapper.AuditoriaMapper;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** RN-04: solo inserta. No hay metodos de update ni delete (y ademas un trigger en BD los rechaza). */
@Component
class AuditoriaR2dbcAdapter implements AuditoriaRepositoryPort {

	private final R2dbcEntityTemplate template;

	AuditoriaR2dbcAdapter(R2dbcEntityTemplate template) {
		this.template = template;
	}

	@Override
	public Mono<Void> registrar(CambioClinico cambio) {
		return Mono.fromSupplier(() -> AuditoriaMapper.aEntidad(cambio)).flatMap(template::insert).then();
	}

}
