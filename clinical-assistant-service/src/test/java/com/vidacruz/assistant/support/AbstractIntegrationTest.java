package com.vidacruz.assistant.support;

import static org.awaitility.Awaitility.await;

import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.port.out.EstadoIndicePort;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Base de los tests de integracion: Redis y Kafka compartidos entre clases (singletons por JVM), proveedor de IA simulado
 * (WireMock) y BlockHound activo. No se usa {@code @ServiceConnection}: Boot 3.5 reconoce los contenedores 1.x, no los de
 * Testcontainers 2.x, asi que las propiedades se registran a mano.
 * <p>
 * Antes de cada test: se vacia SOLO la vista de urgencias (no el indice de protocolos, que se construye una vez por
 * contexto), se espera a que el indice este listo y se cierran/reinician los circuit breakers y los stubs del proveedor.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@AutoConfigureObservability       // por defecto los tests desactivan la exportacion de metricas (Prometheus)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

	/** Coincide con assistant.security.api-key de application-test.yml. */
	protected static final String API_KEY = "test-api-key";

	static {
		BlockHoundSupport.instalar();
	}

	@Autowired
	private WebTestClient webTestClient;

	@Autowired
	protected ReactiveStringRedisTemplate redis;

	@Autowired
	protected EstadoUrgenciasPort vista;

	@Autowired
	protected EstadoIndicePort estadoIndice;

	@Autowired
	protected CircuitBreakerRegistry circuitos;

	@Autowired
	protected MeterRegistry meterRegistry;

	@DynamicPropertySource
	static void propiedades(DynamicPropertyRegistry registry) {
		var redis = Contenedores.redis();
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
		registry.add("spring.kafka.bootstrap-servers", () -> Contenedores.kafka().getBootstrapServers());
		registry.add("spring.ai.openai.base-url", ProveedorLlmSimulado::url);
	}

	@BeforeEach
	void preparar() {
		if (esperarIndiceListo()) {
			ProveedorLlmSimulado.reiniciar();
			circuitos.getAllCircuitBreakers().forEach(cb -> cb.reset());
		}
		redis.scan(ScanOptions.scanOptions().match("asistente:urgencias:*").count(500).build())
				.flatMap(redis::unlink).blockLast(Duration.ofSeconds(10));
		if (esperarIndiceListo()) {
			await().atMost(Duration.ofSeconds(60)).until(estadoIndice::listo);
		}
	}

	@AfterEach
	void restaurarProveedor() {
		ProveedorLlmSimulado.reiniciar();
	}

	/** false en los tests que arrancan con el proveedor de embeddings caido y no quieren que la base lo "arregle". */
	protected boolean esperarIndiceListo() {
		return true;
	}

	/** Cliente con la API key valida. */
	protected WebTestClient api() {
		return webTestClient.mutate().responseTimeout(Duration.ofSeconds(30))
				.defaultHeader("X-API-Key", API_KEY).build();
	}

	/** Cliente sin credenciales. */
	protected WebTestClient anonimo() {
		return webTestClient.mutate().responseTimeout(Duration.ofSeconds(30)).build();
	}

	protected EstadoUrgencias estado() {
		return vista.consultar().block(Duration.ofSeconds(10));
	}

	protected double contador(String nombre, String... tags) {
		var c = meterRegistry.find(nombre).tags(tags).counter();
		return c == null ? 0 : c.count();
	}

}
