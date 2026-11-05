package com.vidacruz.assistant.infrastructure.entrypoint.web.controller.doc;

import com.vidacruz.assistant.infrastructure.entrypoint.web.exception.ErrorApi;

/** Ejemplos JSON de la documentacion OpenAPI (se referencian desde las anotaciones de los controllers). */
public final class EjemplosApi {

	private EjemplosApi() {
	}

	// ---------------------------------------------------------------- valores que se repiten en varios ejemplos

	public static final String CORRELATION_ID = "demo-001";
	/** Instante cualquiera para los campos {@code timestamp} de los ejemplos de error. */
	public static final String FECHA_EJEMPLO = "2026-11-04T15:17:57.285937Z";
	public static final String ULTIMO_EVENTO_EN = "2026-11-04T10:15:00-05:00";
	public static final String CONSULTADO_EN = "2026-11-04T10:15:07.318-05:00";
	/** Conteo por nivel del ejemplo del estado de urgencias (clave = nombre del enum NivelTriaje). */
	public static final String POR_NIVEL = "{\"CRITICO\":1,\"URGENTE\":2,\"URGENTE_2\":2,\"NO_URGENTE\":1,\"NO_URGENTE_2\":1}";

	public static final String ESTADO_URGENCIAS = """
			{
			  "totalActivos": 7,
			  "porNivel": { "CRITICO": 1, "URGENTE": 2, "URGENTE_2": 2, "NO_URGENTE": 1, "NO_URGENTE_2": 1 },
			  "escalados": 2,
			  "ultimoEventoEn": \"""" + ULTIMO_EVENTO_EN + """
			",
			  "consultadoEn": \"""" + CONSULTADO_EN + """
			"
			}
			""";

	public static final String ERROR_401 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			no-autenticado",
			  "title": "No autenticado",
			  "status": 401,
			  "detail": "Falta el header X-API-Key o su valor no es valido",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			"
			}
			""";

	public static final String ERROR_500 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			interno",
			  "title": "Error interno",
			  "status": 500,
			  "detail": "Ocurrio un error interno. Reporta el correlationId al equipo de soporte.",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			"
			}
			""";

	public static final String ERROR_503 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			estado-no-disponible",
			  "title": "Estado de urgencias no disponible",
			  "status": 503,
			  "detail": "La vista de urgencias no esta disponible temporalmente (el almacen Redis no responde). Reintenta en unos segundos.",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			"
			}
			""";

	// ---------------------------------------------------------------- POST /asistente/consulta

	public static final String CONSULTA_RESPONDIDA = """
			{
			  "respuesta": "Reconfirmar la presión tras 5 minutos de reposo. No reducir la presión más de un 25% en la primera hora. Vigilar signos de encefalopatía.",
			  "fuentes": [ { "protocolo": "PROTO-001", "seccion": "Medidas iniciales" } ]
			}
			""";

	public static final String CONSULTA_SIN_PROTOCOLO = """
			{
			  "respuesta": "No existe un protocolo disponible en el corpus oficial para responder esta pregunta.",
			  "fuentes": []
			}
			""";

	public static final String ERROR_400_CONSULTA = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): pregunta",
			  "instance": "/asistente/consulta",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			",
			  "errores": [
			    { "campo": "pregunta", "valor": "hola", "mensaje": "debe tener entre 5 y 500 caracteres" }
			  ]
			}
			""";

	public static final String ERROR_429 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			demasiadas-peticiones",
			  "title": "Demasiadas peticiones",
			  "status": 429,
			  "detail": "Superaste el limite de 20 consultas cada 60 segundos. Reintenta en 60 segundos",
			  "instance": "/asistente/consulta",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			"
			}
			""";

	public static final String ERROR_503_LLM = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			asistente-no-disponible",
			  "title": "Asistente clínico no disponible",
			  "status": 503,
			  "detail": "El asistente clínico no está disponible temporalmente; consulte los protocolos impresos",
			  "instance": "/asistente/consulta",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			",
			  "operacion": "chat"
			}
			""";

	public static final String ERROR_503_INDICE = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			indice-no-disponible",
			  "title": "Índice de protocolos no disponible",
			  "status": 503,
			  "detail": "El asistente clínico se está preparando (indexando los protocolos); reintente en unos segundos. Mientras tanto, consulte los protocolos impresos",
			  "instance": "/asistente/consulta",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_EJEMPLO + """
			"
			}
			""";

}
