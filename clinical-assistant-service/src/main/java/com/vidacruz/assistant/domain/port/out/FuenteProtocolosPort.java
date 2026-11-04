package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.Protocolo;
import java.util.List;
import reactor.core.publisher.Mono;

/** De donde sale el corpus oficial (hoy, archivos Markdown en resources/protocolos). */
public interface FuenteProtocolosPort {

	Mono<List<Protocolo>> cargar();

}
