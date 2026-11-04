package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.AplicacionEvento;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.OperacionEstado;
import java.time.Instant;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Almacen de la vista de urgencias. {@link #aplicar} debe ser <b>atomica e idempotente</b> por {@code eventId}:
 * verificar que el evento no se proceso, modificar la vista, ajustar contadores y marcar el evento como procesado,
 * todo como una sola operacion. Si el almacen no responde, falla con EstadoUrgenciasNoDisponibleException.
 */
public interface EstadoUrgenciasPort {

	Mono<AplicacionEvento> aplicar(UUID eventId, Instant ocurridoEn, OperacionEstado operacion);

	Mono<EstadoUrgencias> consultar();

}
