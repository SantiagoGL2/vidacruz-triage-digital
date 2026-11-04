package com.vidacruz.assistant.support;

import java.util.concurrent.atomic.AtomicBoolean;
import reactor.blockhound.BlockHound;

/**
 * Instala BlockHound una sola vez por JVM: cualquier llamada bloqueante en un hilo no bloqueante (event loop de
 * Netty/Lettuce, parallel, etc.) hace fallar el test. Los hilos de JUnit y boundedElastic no se vigilan.
 * <p>
 * Las excepciones de abajo son bloqueos conocidos e inofensivos de librerias (no del codigo del proyecto):
 * el logging sincrono a consola, la semilla perezosa de UUID.randomUUID, la carga perezosa de clases y la
 * generacion (unica, cacheada) del OpenAPI de springdoc.
 */
public final class BlockHoundSupport {

	private static final AtomicBoolean INSTALADO = new AtomicBoolean();

	private BlockHoundSupport() {
	}

	public static void instalar() {
		if (INSTALADO.compareAndSet(false, true)) {
			// install(integraciones) y no builder().install(): solo asi se cargan las integraciones de Reactor/Netty
			// (las que marcan los schedulers parallel/single y los event loops como no bloqueantes).
			BlockHound.install(builder -> builder
					.allowBlockingCallsInside("ch.qos.logback.core.UnsynchronizedAppenderBase", "doAppend")
					.allowBlockingCallsInside("ch.qos.logback.core.AppenderBase", "doAppend")
					.allowBlockingCallsInside("ch.qos.logback.core.OutputStreamAppender", "subAppend")
					.allowBlockingCallsInside("java.util.UUID", "randomUUID")
					// springdoc genera el documento OpenAPI de forma perezosa en la primera peticion a /v3/api-docs
					.allowBlockingCallsInside("org.springdoc.api.AbstractOpenApiResource", "getOpenApi")
					// recurso estatico de Swagger UI (jar de webjars): limitacion del resolver de recursos de WebFlux
					.allowBlockingCallsInside("org.springframework.core.io.ClassPathResource", "isReadable")
					// primera carga de una clase desde un jar: lectura de disco inevitable
					.allowBlockingCallsInside("java.lang.ClassLoader", "loadClass")
					.allowBlockingCallsInside("jdk.internal.loader.BuiltinClassLoader", "loadClassOrNull"));
		}
	}

}
