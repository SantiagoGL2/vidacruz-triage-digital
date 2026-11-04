package com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Decide si un fallo al llamar al proveedor de IA es TRANSITORIO y, por tanto, vale la pena reintentar: timeout, errores
 * de conexion/E-S, 5xx y 429. Nunca reintenta un 4xx de configuracion (clave invalida, modelo inexistente, peticion mal
 * formada) ni nada que no se reconozca. Spring AI convierte los errores HTTP: 5xx -> TransientAiException y cualquier 4xx ->
 * NonTransientAiException con el mensaje «HTTP CODIGO - cuerpo»; el 429 se distingue por ese codigo.
 * Se referencia desde application.yml (resilience4j.retry.instances.*.retry-exception-predicate).
 */
public class ErrorTransitorioProveedor implements Predicate<Throwable> {

	/** Spring AI escribe «HTTP 429 - cuerpo» (segun la version, sin el prefijo HTTP). */
	private static final Pattern MENSAJE_429 = Pattern.compile("^(HTTP )?429(\\D|$)");

	@Override
	public boolean test(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof TimeoutException || t instanceof TransientAiException || t instanceof IOException
					|| t instanceof JedisConnectionException) {
				return true;
			}
			if (t instanceof NonTransientAiException) {
				return t.getMessage() != null && MENSAJE_429.matcher(t.getMessage()).find();
			}
			if (t instanceof RestClientResponseException http) {
				int estado = http.getStatusCode().value();
				return estado >= 500 || estado == 429;
			}
			// ResourceAccessException (RestClient) envuelve la IOException real; el bucle llega a ella
		}
		return false;
	}

}
