package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.FragmentoProtocolo;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Indice vectorial de fragmentos. {@link #sincronizar} es idempotente: deja el indice exactamente igual a la lista
 * recibida, calculando embeddings SOLO de lo nuevo o cambiado (por hash) y borrando lo que ya no existe.
 */
public interface IndiceProtocolosPort {

	Mono<ResultadoSincronizacion> sincronizar(List<FragmentoProtocolo> deseados);

}
