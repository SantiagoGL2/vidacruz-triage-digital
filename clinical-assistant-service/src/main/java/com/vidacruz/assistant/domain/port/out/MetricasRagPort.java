package com.vidacruz.assistant.domain.port.out;

import java.time.Duration;

/** Metricas del flujo de consulta RAG. */
public interface MetricasRagPort {

	/** {@code resultado}: respondida, sin_protocolo o degradado. */
	void consulta(String resultado);

	void citasDescartadas(int cantidad);

	/** Una llamada al proveedor. {@code operacion}: chat o embedding; {@code resultado}: ok o error. */
	void latenciaLlm(String operacion, String resultado, Duration duracion);

	/** Tokens que informa el proveedor. {@code tipo}: prompt o completion. */
	void tokens(String tipo, long cantidad);

}
