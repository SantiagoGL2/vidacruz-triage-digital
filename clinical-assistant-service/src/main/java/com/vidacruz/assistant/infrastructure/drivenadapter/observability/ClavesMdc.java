package com.vidacruz.assistant.infrastructure.drivenadapter.observability;

import com.vidacruz.assistant.application.context.ContextoCorrelacion;

/**
 * Claves del MDC de logging. Es la misma clave que usa el Context de Reactor ({@link ContextoCorrelacion#CLAVE}): asi el
 * puente Reactor -> MDC copia el valor sin traducciones, y {@code logging.pattern.level} la lee como {@code %X{correlationId}}.
 */
public final class ClavesMdc {

	public static final String CORRELATION_ID = ContextoCorrelacion.CLAVE;

	private ClavesMdc() {
	}

}
