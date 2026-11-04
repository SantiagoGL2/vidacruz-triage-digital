package com.vidacruz.assistant.application.usecase;

import com.vidacruz.assistant.application.port.in.IndexarProtocolosUseCase;
import com.vidacruz.assistant.domain.exception.ProtocoloInvalidoException;
import com.vidacruz.assistant.domain.model.ResultadoSincronizacion;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.FuenteProtocolosPort;
import com.vidacruz.assistant.domain.port.out.IndiceProtocolosPort;
import com.vidacruz.assistant.domain.service.FragmentadorProtocolos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Corpus -> fragmentos (uno por seccion) -> indice. Un fallo (proveedor de embeddings, Redis, protocolo mal formado) deja
 * el estado en «no listo» con el motivo y se propaga: quien lo invoca reintenta. Un corpus vacio es un error (evita
 * borrar el indice por un despliegue sin protocolos).
 */
public class IndexarProtocolosUseCaseImpl implements IndexarProtocolosUseCase {

	private static final Logger log = LoggerFactory.getLogger(IndexarProtocolosUseCaseImpl.class);

	private final FuenteProtocolosPort fuente;
	private final FragmentadorProtocolos fragmentador;
	private final IndiceProtocolosPort indice;
	private final EstadoIndicePort estado;

	public IndexarProtocolosUseCaseImpl(FuenteProtocolosPort fuente, FragmentadorProtocolos fragmentador,
			IndiceProtocolosPort indice, EstadoIndicePort estado) {
		this.fuente = fuente;
		this.fragmentador = fragmentador;
		this.indice = indice;
		this.estado = estado;
	}

	@Override
	public Mono<ResultadoSincronizacion> indexar() {
		return fuente.cargar()
				.flatMap(protocolos -> {
					if (protocolos.isEmpty()) {
						return Mono.error(new ProtocoloInvalidoException("El corpus no contiene ningun protocolo"));
					}
					return indice.sincronizar(fragmentador.fragmentar(protocolos));
				})
				.doOnNext(resultado -> {
					estado.marcarListo(resultado);
					log.info("Indice de protocolos listo: {} fragmentos (agregados={}, actualizados={}, eliminados={}, sinCambios={})",
							resultado.fragmentosIndexados(), resultado.agregados(), resultado.actualizados(),
							resultado.eliminados(), resultado.sinCambios());
				})
				.doOnError(e -> estado.marcarFallo(e.getClass().getSimpleName() + ": " + e.getMessage()));
	}

	@Override
	public void asumirIndiceExistente() {
		estado.marcarListo(new ResultadoSincronizacion(0, 0, 0, 0));
		log.warn("Indexacion de protocolos deshabilitada: se asume que el indice ya existe");
	}

}
