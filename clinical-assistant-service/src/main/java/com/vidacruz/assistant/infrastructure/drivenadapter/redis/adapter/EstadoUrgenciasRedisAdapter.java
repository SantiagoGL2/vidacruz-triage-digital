package com.vidacruz.assistant.infrastructure.drivenadapter.redis.adapter;

import com.vidacruz.assistant.domain.exception.EstadoUrgenciasNoDisponibleException;
import com.vidacruz.assistant.domain.model.AplicacionEvento;
import com.vidacruz.assistant.domain.model.EstadoUrgencias;
import com.vidacruz.assistant.domain.model.OperacionEstado;
import com.vidacruz.assistant.domain.model.ResultadoAplicacion;
import com.vidacruz.assistant.domain.model.enums.NivelTriaje;
import com.vidacruz.assistant.domain.port.out.EstadoUrgenciasPort;
import com.vidacruz.assistant.infrastructure.config.AsistenteEstadoUrgenciasProperties;
import io.lettuce.core.RedisCommandExecutionException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Vista de urgencias en Redis.
 * <pre>
 * {prefijo}paciente:{id}   hash  nivel, estado, escalado, fechaRegistro   (un hash por paciente ACTIVO)
 * {prefijo}contadores      hash  CRITICO..NO_URGENTE_2 y escalados        (contadores de activos)
 * {prefijo}ultimo-evento   string  occurredAt del evento mas reciente aplicado (microsegundos desde epoch)
 * {prefijo}evento:{id}     string  marca de "eventId ya procesado", con TTL
 * </pre>
 * Aplicar un evento es UN script Lua ({@code redis/aplicar-evento.lua}): verificar el eventId, tocar el paciente,
 * ajustar contadores y marcar el eventId es atomico, asi que dos replicas o un reintento no pueden dejar la vista a medias
 * ni sumar dos veces. El script se lee del classpath una sola vez, al construir el adapter; despues se ejecuta por
 * EVALSHA (Spring lo cae a EVAL si Redis no lo conoce).
 */
@Component
class EstadoUrgenciasRedisAdapter implements EstadoUrgenciasPort {

	private static final String SCRIPT = "redis/aplicar-evento.lua";
	private static final String CAMPO_ESCALADOS = "escalados";

	private final ReactiveStringRedisTemplate redis;
	private final RedisScript<List> script;
	private final String prefijo;
	private final long ttlSegundos;

	EstadoUrgenciasRedisAdapter(ReactiveStringRedisTemplate redis, AsistenteEstadoUrgenciasProperties propiedades) {
		this.redis = redis;
		this.prefijo = propiedades.keyPrefix();
		this.ttlSegundos = propiedades.ttlDeduplicacion().toSeconds();
		// El texto se lee UNA vez, aqui (arranque). Con un recurso de classpath, DefaultRedisScript lo volveria a leer
		// del disco en cada ejecucion, bloqueando el hilo de Netty que atiende la respuesta de Redis.
		this.script = new DefaultRedisScript<>(cargarScript(), List.class);
	}

	@Override
	public Mono<AplicacionEvento> aplicar(UUID eventId, Instant ocurridoEn, OperacionEstado operacion) {
		List<String> keys = List.of(
				prefijo + "evento:" + eventId,
				prefijo + "paciente:" + operacion.pacienteId(),
				prefijo + "contadores",
				prefijo + "ultimo-evento");
		List<String> args = argumentos(operacion, ocurridoEn);
		return redis.execute(script, keys, args)
				.next()
				.map(EstadoUrgenciasRedisAdapter::aAplicacion)
				.onErrorMap(EstadoUrgenciasRedisAdapter::esTransitorio, e -> noDisponible("aplicar el evento " + eventId, e));
	}

	@Override
	public Mono<EstadoUrgencias> consultar() {
		Mono<Map<Object, Object>> contadores = redis.opsForHash().entries(prefijo + "contadores")
				.collectMap(e -> e.getKey(), e -> e.getValue());
		Mono<String> ultimo = redis.opsForValue().get(prefijo + "ultimo-evento").defaultIfEmpty("");
		return Mono.zip(contadores, ultimo)
				.map(t -> aEstado(t.getT1(), t.getT2()))
				.onErrorMap(EstadoUrgenciasRedisAdapter::esTransitorio, e -> noDisponible("consultar la vista", e));
	}

	private static String cargarScript() {
		try (var in = new ClassPathResource(SCRIPT).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo cargar el script " + SCRIPT, e);
		}
	}

	/** ARGV del script: operacion, nivel, fechaRegistro, occurredAt (micros) y TTL (segundos) de la marca del eventId. */
	private List<String> argumentos(OperacionEstado operacion, Instant ocurridoEn) {
		String micros = String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, ocurridoEn));
		String ttl = String.valueOf(ttlSegundos);
		return switch (operacion) {
			case OperacionEstado.Registrar r -> List.of("REGISTRAR", r.nivel().name(), r.fechaRegistro().toString(), micros, ttl);
			case OperacionEstado.PasarAConsulta c -> List.of("CONSULTA", "", "", micros, ttl);
			case OperacionEstado.Escalar e -> List.of("ESCALAR", "", "", micros, ttl);
			case OperacionEstado.DarDeAlta a -> List.of("ALTA", "", "", micros, ttl);
		};
	}

	/** Respuesta del script: {codigo, CRITICO, URGENTE, URGENTE_2, NO_URGENTE, NO_URGENTE_2, escalados, ultimoMicros}. */
	private static AplicacionEvento aAplicacion(List<?> r) {
		ResultadoAplicacion resultado = switch (((Number) r.get(0)).intValue()) {
			case 1 -> ResultadoAplicacion.APLICADO;
			case 2 -> ResultadoAplicacion.DUPLICADO;
			case 3 -> ResultadoAplicacion.IGNORADO_PACIENTE_DESCONOCIDO;
			case 4 -> ResultadoAplicacion.IGNORADO_YA_REGISTRADO;
			case 5 -> ResultadoAplicacion.SIN_CAMBIO;
			default -> throw new IllegalStateException("Codigo de resultado desconocido del script: " + r.get(0));
		};
		Map<NivelTriaje, Long> porNivel = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			porNivel.put(nivel, ((Number) r.get(nivel.numero())).longValue());
		}
		long escalados = ((Number) r.get(6)).longValue();
		long micros = ((Number) r.get(7)).longValue();
		return new AplicacionEvento(resultado,
				new EstadoUrgencias(porNivel, escalados, micros == 0 ? null : instante(micros)));
	}

	private static EstadoUrgencias aEstado(Map<Object, Object> contadores, String ultimo) {
		Map<NivelTriaje, Long> porNivel = new EnumMap<>(NivelTriaje.class);
		for (NivelTriaje nivel : NivelTriaje.values()) {
			porNivel.put(nivel, numero(contadores.get(nivel.name())));
		}
		return new EstadoUrgencias(porNivel, numero(contadores.get(CAMPO_ESCALADOS)),
				ultimo.isEmpty() ? null : instante(Long.parseLong(ultimo)));
	}

	private static long numero(Object valor) {
		return valor == null ? 0L : Long.parseLong(valor.toString());
	}

	private static Instant instante(long micros) {
		return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
	}

	/**
	 * Transitorio = Redis no responde (conexion, timeout). Segun el comando, el fallo llega traducido por Spring
	 * (DataAccessException) o como excepcion cruda de Lettuce (p. ej. RedisCommandTimeoutException), asi que se aceptan
	 * ambas. Un error del propio script (RedisCommandExecutionException) es un bug y no se disfraza de "no disponible".
	 */
	private static boolean esTransitorio(Throwable e) {
		boolean redis = false;
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof RedisCommandExecutionException) {
				return false;
			}
			redis |= t instanceof DataAccessException || t instanceof io.lettuce.core.RedisException;
		}
		return redis;
	}

	private static EstadoUrgenciasNoDisponibleException noDisponible(String accion, Throwable causa) {
		return new EstadoUrgenciasNoDisponibleException("Redis no responde al " + accion, causa);
	}

}
