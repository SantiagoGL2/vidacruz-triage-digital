package com.vidacruz.assistant.application.port.in;

import com.vidacruz.assistant.application.command.ConsultarProtocoloComando;
import com.vidacruz.assistant.application.result.RespuestaConsulta;
import reactor.core.publisher.Mono;

/** RF-04 parte A: responde una pregunta con RAG sobre el corpus de protocolos (RN-05). */
public interface ConsultarProtocoloUseCase {

	Mono<RespuestaConsulta> consultar(ConsultarProtocoloComando comando);

}
