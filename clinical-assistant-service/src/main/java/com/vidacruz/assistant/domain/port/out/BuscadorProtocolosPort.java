package com.vidacruz.assistant.domain.port.out;

import com.vidacruz.assistant.domain.model.FragmentoRecuperado;
import com.vidacruz.assistant.domain.model.ParametrosRecuperacion;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Barrera 1: busqueda semantica top-K con umbral de similitud. Devuelve solo fragmentos que superan el umbral,
 * ordenados por puntuacion descendente (puede ser vacia). Falla con AsistenteNoDisponibleException si el proveedor de
 * embeddings no responde.
 */
public interface BuscadorProtocolosPort {

	Mono<List<FragmentoRecuperado>> buscar(String pregunta, ParametrosRecuperacion parametros);

}
