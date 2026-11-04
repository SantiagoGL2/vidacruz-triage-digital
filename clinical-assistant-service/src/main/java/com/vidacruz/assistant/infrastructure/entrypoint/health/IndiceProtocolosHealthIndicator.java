package com.vidacruz.assistant.infrastructure.entrypoint.health;

import com.vidacruz.assistant.domain.model.EstadoIndice;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import java.util.Locale;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Indicador {@code indiceProtocolos}, parte de la READINESS (no de la liveness): UP cuando el indice de protocolos esta
 * indexado; DOWN mientras se indexa por primera vez o si la indexacion esta fallando (con el detalle del error). Solo informa
 * de la indexacion: una vez listo, que el proveedor de IA se caiga no lo pone en DOWN (eso es modo degradado de la consulta).
 * <p>
 * Detalles: {@code estado}, {@code fragmentosIndexados}, {@code detalleError} y {@code actualizadoEn} (los dos ultimos solo
 * cuando existen) y {@code descripcion}, el texto de messages.properties para ese estado.
 */
@Component
class IndiceProtocolosHealthIndicator implements ReactiveHealthIndicator {

	private final EstadoIndicePort estado;
	private final MessageSource mensajes;

	IndiceProtocolosHealthIndicator(EstadoIndicePort estado, MessageSource mensajes) {
		this.estado = estado;
		this.mensajes = mensajes;
	}

	@Override
	public Mono<Health> health() {
		return Mono.fromSupplier(() -> aHealth(estado.actual()));
	}

	private Health aHealth(EstadoIndice indice) {
		Health.Builder health = indice.listo() ? Health.up() : Health.down();
		health.withDetail("estado", indice.estado().name())
				.withDetail("fragmentosIndexados", indice.fragmentosIndexados())
				.withDetail("descripcion", mensajes.getMessage(
						"indice.estado." + indice.estado().name().toLowerCase(Locale.ROOT), null, Locale.getDefault()));
		if (indice.detalleError() != null) {
			health.withDetail("detalleError", indice.detalleError());
		}
		if (indice.actualizadoEn() != null) {
			health.withDetail("actualizadoEn", indice.actualizadoEn().toString());
		}
		return health.build();
	}

}
