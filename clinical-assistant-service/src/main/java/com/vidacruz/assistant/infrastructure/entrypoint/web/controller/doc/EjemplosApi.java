package com.vidacruz.assistant.infrastructure.entrypoint.web.controller.doc;

/** Ejemplos JSON de la documentacion OpenAPI (se referencian desde las anotaciones de los controllers). */
public final class EjemplosApi {

	private EjemplosApi() {
	}

	public static final String ESTADO_URGENCIAS = """
			{
			  "totalActivos": 7,
			  "porNivel": { "CRITICO": 1, "URGENTE": 2, "URGENTE_2": 2, "NO_URGENTE": 1, "NO_URGENTE_2": 1 },
			  "escalados": 2,
			  "ultimoEventoEn": "2026-11-04T10:15:00-05:00",
			  "consultadoEn": "2026-11-04T10:15:07.318-05:00"
			}
			""";

	public static final String ERROR_401 = """
			{
			  "type": "urn:vidacruz:asistente:problema:no-autenticado",
			  "title": "No autenticado",
			  "status": 401,
			  "detail": "Falta el header X-API-Key o su valor no es valido",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

	public static final String ERROR_500 = """
			{
			  "type": "urn:vidacruz:asistente:problema:interno",
			  "title": "Error interno",
			  "status": 500,
			  "detail": "Ocurrio un error interno. Reporta el correlationId al equipo de soporte.",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

	public static final String ERROR_503 = """
			{
			  "type": "urn:vidacruz:asistente:problema:estado-no-disponible",
			  "title": "Estado de urgencias no disponible",
			  "status": 503,
			  "detail": "La vista de urgencias no esta disponible temporalmente (el almacen Redis no responde). Reintenta en unos segundos.",
			  "instance": "/asistente/estado-urgencias",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
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
			  "type": "urn:vidacruz:asistente:problema:validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): pregunta",
			  "instance": "/asistente/consulta",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z",
			  "errores": [
			    { "campo": "pregunta", "valor": "hola", "mensaje": "debe tener entre 5 y 500 caracteres" }
			  ]
			}
			""";

	public static final String ERROR_503_LLM = """
			{
			  "type": "urn:vidacruz:asistente:problema:asistente-no-disponible",
			  "title": "Asistente clínico no disponible",
			  "status": 503,
			  "detail": "El asistente clínico no está disponible temporalmente; consulte los protocolos impresos",
			  "instance": "/asistente/consulta",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z",
			  "operacion": "chat"
			}
			""";

	public static final String ERROR_503_INDICE = """
			{
			  "type": "urn:vidacruz:asistente:problema:indice-no-disponible",
			  "title": "Índice de protocolos no disponible",
			  "status": 503,
			  "detail": "El asistente clínico se está preparando (indexando los protocolos); reintente en unos segundos. Mientras tanto, consulte los protocolos impresos",
			  "instance": "/asistente/consulta",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

}
