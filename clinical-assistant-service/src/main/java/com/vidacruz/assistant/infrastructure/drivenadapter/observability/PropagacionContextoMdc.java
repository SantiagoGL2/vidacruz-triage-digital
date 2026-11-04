package com.vidacruz.assistant.infrastructure.drivenadapter.observability;

import io.micrometer.context.ContextRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Puente Reactor Context -> MDC. El correlationId vive en el Context de Reactor; con
 * {@code spring.reactor.context-propagation=auto} Reactor lo copia al MDC alrededor de cada operador, de modo que
 * los logs lo incluyen sin usar ThreadLocal a mano.
 */
@Component
class PropagacionContextoMdc implements InitializingBean {

	private static final AtomicBoolean REGISTRADO = new AtomicBoolean();

	@Override
	public void afterPropertiesSet() {
		if (REGISTRADO.compareAndSet(false, true)) {
			ContextRegistry.getInstance().registerThreadLocalAccessor(ClavesMdc.CORRELATION_ID,
					() -> MDC.get(ClavesMdc.CORRELATION_ID),
					valor -> MDC.put(ClavesMdc.CORRELATION_ID, valor),
					() -> MDC.remove(ClavesMdc.CORRELATION_ID));
		}
	}

}
