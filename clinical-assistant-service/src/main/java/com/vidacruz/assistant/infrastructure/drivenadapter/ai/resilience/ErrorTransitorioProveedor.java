package com.vidacruz.assistant.infrastructure.drivenadapter.ai.resilience;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;
import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Decide si un fallo al llamar al proveedor de IA es TRANSITORIO y, por tanto, vale la pena reintentar: errores de
 * conexion/E-S (incluido no poder conectar a tiempo), 5xx y 429. <b>Nunca reintenta un timeout de respuesta</b> (el del
 * TimeLimiter ni el de lectura del cliente HTTP): si el proveedor ya tardo todo el presupuesto, otro intento tarda lo mismo y
 * el peor caso de la consulta se duplicaria. Tampoco reintenta un 4xx de configuracion (clave invalida, modelo inexistente, peticion mal
 * formada) ni nada que no se reconozca. Spring AI convierte los errores HTTP: 5xx -> TransientAiException y cualquier 4xx ->
 * NonTransientAiException con el mensaje «HTTP CODIGO - cuerpo»; el 429 se distingue por ese codigo.
 * Se referencia desde application.yml (resilience4j.retry.instances.*.retry-exception-predicate).
 */
public class ErrorTransitorioProveedor implements Predicate<Throwable> {

	/** Spring AI escribe «HTTP 429 - cuerpo» (segun la version, sin el prefijo HTTP). */
	private static final Pattern MENSAJE_429 = Pattern.compile("^(HTTP )?429(\\D|$)");

	/** No poder abrir la conexion es un error de red (se reintenta); esperar la respuesta de un proveedor lento no. */
	private static boolean esTimeoutDeConexion(Throwable t) {
		return t instanceof HttpConnectTimeoutException
				|| (t.getMessage() != null && t.getMessage().toLowerCase(Locale.ROOT).contains("connect"));
	}

	@Override
	public boolean test(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof TimeoutException) {      // TimeLimiter
				return false;
			}
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return esTimeoutDeConexion(t);        // el de lectura (el proveedor tardo en responder) no se reintenta
			}
			if (t instanceof TransientAiException || t instanceof IOException || t instanceof JedisConnectionException) {
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
