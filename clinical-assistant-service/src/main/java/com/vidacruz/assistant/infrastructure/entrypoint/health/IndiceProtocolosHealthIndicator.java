package com.vidacruz.assistant.infrastructure.entrypoint.health;

import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Indicador {@code indiceProtocolos}, parte de la READINESS (no de la liveness): UP cuando el indice de protocolos esta
 * indexado; DOWN mientras se indexa por primera vez o si la indexacion esta fallando (con el motivo). Solo informa de
 * la indexacion: una vez listo, que el proveedor de IA se caiga no lo pone en DOWN (eso es modo degradado de la consulta).
 */
@Component
class IndiceProtocolosHealthIndicator implements ReactiveHealthIndicator {

	private final EstadoIndicePort estado;

	IndiceProtocolosHealthIndicator(EstadoIndicePort estado) {
		this.estado = estado;
	}

	@Override
	public Mono<Health> health() {
		return Mono.fromSupplier(() -> estado.listo()
				? Health.up().withDetail("fragmentos", estado.fragmentosIndexados()).build()
				: Health.down().withDetail("motivo", estado.motivo()).build());
	}

}
