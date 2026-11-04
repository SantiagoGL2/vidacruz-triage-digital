package com.vidacruz.assistant.infrastructure.entrypoint.web.exception;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Catalogo de errores de la API (documentado en docs/errores.md). Cada error fija su status HTTP y un {@code type} URI
 * estable (RFC 7807) que los clientes pueden usar para reconocerlo sin depender del texto. El {@code title} y el mensaje
 * ({@code detail}) que ve el usuario NO estan aqui: viven en {@code messages.properties} (claves {@code error.<codigo>.titulo}
 * y {@code error.<codigo>.mensaje}) y se resuelven con MessageSource segun el idioma de la peticion.
 * <p>
 * Los errores HTTP genericos del framework (404 de una ruta, 405, 415...) no estan en el catalogo: conservan su status y usan
 * el type {@code <URI_BASE>http-<status>}.
 */
public enum ErrorApi {

	VALIDACION(HttpStatus.BAD_REQUEST, "validacion"),
	JSON_MALFORMADO(HttpStatus.BAD_REQUEST, "json-malformado"),
	SOLICITUD_INVALIDA(HttpStatus.BAD_REQUEST, "solicitud-invalida"),
	NO_AUTENTICADO(HttpStatus.UNAUTHORIZED, "no-autenticado"),
	ASISTENTE_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE, "asistente-no-disponible"),
	INDICE_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE, "indice-no-disponible"),
	ESTADO_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE, "estado-no-disponible"),
	ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR, "interno");

	/** Prefijo de todos los {@code type}. Constante de compilacion: tambien la usan los ejemplos de OpenAPI. */
	public static final String URI_BASE = "https://vidacruz.example/errores/";

	private final HttpStatus status;
	private final String codigo;

	ErrorApi(HttpStatus status, String codigo) {
		this.status = status;
		this.codigo = codigo;
	}

	public HttpStatus status() {
		return status;
	}

	/** Ultimo tramo del type, p. ej. {@code indice-no-disponible}. */
	public String codigo() {
		return codigo;
	}

	public URI tipo() {
		return URI.create(URI_BASE + codigo);
	}

	public String claveTitulo() {
		return "error." + codigo + ".titulo";
	}

	public String claveMensaje() {
		return "error." + codigo + ".mensaje";
	}

}
