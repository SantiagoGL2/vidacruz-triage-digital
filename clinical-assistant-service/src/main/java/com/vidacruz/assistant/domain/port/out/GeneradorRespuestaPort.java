package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.SalidaGenerador;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Barrera 2: pide al modelo de lenguaje una respuesta usando SOLO los fragmentos dados. La salida no es confiable: la
 * valida el dominio. Falla con AsistenteNoDisponibleException si el proveedor de chat no responde.
 */
public interface GeneradorRespuestaPort {

	Mono<SalidaGenerador> generar(String pregunta, List<FragmentoRecuperado> contexto);

}
