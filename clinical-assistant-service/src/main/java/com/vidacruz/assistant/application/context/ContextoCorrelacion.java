package com.vidacruz.assistant.application.context;

import reactor.core.publisher.Mono;

/** Clave del correlationId en el Context de Reactor (nunca ThreadLocal) y utilidad para leerlo. */
public final class ContextoCorrelacion {

	public static final String CLAVE = "correlationId";
	public static final String SIN_CORRELACION = "sin-correlacion";

	private ContextoCorrelacion() {
	}

	public static Mono<String> actual() {
		return Mono.deferContextual(ctx -> Mono.just(ctx.<String>getOrDefault(CLAVE, SIN_CORRELACION)));
	}

}
