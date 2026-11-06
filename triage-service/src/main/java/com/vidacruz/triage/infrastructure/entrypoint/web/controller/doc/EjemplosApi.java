package com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc;

import com.vidacruz.triage.infrastructure.entrypoint.web.exception.ErrorApi;

/**
 * Ejemplos JSON de la documentacion OpenAPI (se referencian desde las anotaciones de los controllers). Los pacientes E1 y E5 son los de
 * "Datos de referencia" del enunciado.
 */
public final class EjemplosApi {

	private EjemplosApi() {
	}

	// ---------------------------------------------------------------- valores que se repiten en varios ejemplos

	/** Paciente del enunciado E1 (CRITICO) usado como protagonista de los ejemplos. */
	// Descripciones de respuesta repetidas en varios endpoints
	public static final String DESC_401_API_KEY = "Falta el header `X-API-Key` o es invalido.";
	public static final String DESC_404_PACIENTE = "No existe un paciente con ese id.";
	public static final String DESC_500 = "Error interno inesperado.";
	public static final String DESC_ID_PACIENTE = "Identificador del paciente";

	public static final String ID_PACIENTE = "e62f5bb0-848c-4343-9887-19cc8fcf3a0a";
	public static final String ID_EVENTO_ALERTA = "ac2bf6bf-3a64-4d0e-8d7e-0a8f9d6a7b21";
	public static final String FECHA_REGISTRO = "2026-11-04T15:17:57.285937Z";
	public static final String FECHA_PRIMERA_CONSULTA = "2026-11-04T15:21:10.402118Z";
	public static final String FECHA_ESCALAMIENTO = "2026-11-04T15:23:30.100Z";
	public static final String CORRELATION_ID = "demo-001";
	public static final String CORRELATION_ESCALAMIENTO = "escalamiento-sla-dc1f9056";
	/** Inicio del rango del reporte (tambien es la primera hora del ejemplo). */
	public static final String INICIO_REPORTE = "2026-11-04T00:00:00-05:00";
	public static final String FIN_REPORTE_PARAMETRO = "2026-11-05T00:00:00-05:00";
	public static final String HORA_REPORTE = "2026-11-04T08:00:00-05:00";
	/** Conteo por nivel del ejemplo del reporte (clave = nombre del enum NivelTriaje). */
	public static final String POR_NIVEL = "{\"CRITICO\":1,\"URGENTE\":2,\"URGENTE_2\":4,\"NO_URGENTE\":3,\"NO_URGENTE_2\":2}";

	// ---------------------------------------------------------------- POST /triage/pacientes

	/** E1: FC 130 -> CRITICO (RN-01, signo vital critico). */
	public static final String REGISTRO_E1 = """
			{
			  "nombreCompleto": "Carlos Andrés Pérez",
			  "edad": 58,
			  "frecuenciaCardiaca": 130,
			  "presionSistolica": 180,
			  "presionDiastolica": 110,
			  "spo2": 97,
			  "temperatura": 36.8,
			  "dolor": 8,
			  "gcs": 15,
			  "quejaPrincipal": "Dolor torácico"
			}
			""";

	/** E5: queja administrativa -> NO_URGENTE_2. */
	public static final String REGISTRO_E5 = """
			{
			  "nombreCompleto": "Lucía Fernanda Rojas",
			  "edad": 34,
			  "frecuenciaCardiaca": 70,
			  "presionSistolica": 115,
			  "presionDiastolica": 75,
			  "spo2": 99,
			  "temperatura": 36.4,
			  "dolor": 0,
			  "gcs": 15,
			  "quejaPrincipal": "Renovación de recetario"
			}
			""";

	public static final String REGISTRADO_E1 = """
			{
			  "id": \"""" + ID_PACIENTE + """
			",
			  "nivel": "CRITICO",
			  "nivelNumero": 1,
			  "slaMinutos": 5,
			  "estado": "REGISTRADO",
			  "fechaRegistro": \"""" + FECHA_REGISTRO + """
			"
			}
			""";

	public static final String REGISTRADO_E5 = """
			{
			  "id": "7b1d3a52-0c4e-4e0b-9a55-3f0f6f2f9c10",
			  "nivel": "NO_URGENTE_2",
			  "nivelNumero": 5,
			  "slaMinutos": 120,
			  "estado": "REGISTRADO",
			  "fechaRegistro": "2026-11-04T15:20:03.114522Z"
			}
			""";

	// ---------------------------------------------------------------- pacientes: cambio de estado y consulta

	public static final String ESTADO_EN_CONSULTA = """
			{ "estado": "EN_CONSULTA" }
			""";

	/** Transicion invalida: el paciente ya esta en ALTA. */
	public static final String ESTADO_INVALIDO = """
			{ "estado": "EN_CONSULTA" }
			""";

	public static final String PACIENTE = """
			{
			  "id": \"""" + ID_PACIENTE + """
			",
			  "nombreCompleto": "Carlos Andrés Pérez",
			  "edad": 58,
			  "signosVitales": {
			    "frecuenciaCardiaca": 130,
			    "presionSistolica": 180,
			    "presionDiastolica": 110,
			    "spo2": 97,
			    "temperatura": 36.8,
			    "dolor": 8,
			    "gcs": 15
			  },
			  "quejaPrincipal": "Dolor torácico",
			  "nivel": "CRITICO",
			  "nivelNumero": 1,
			  "slaMinutos": 5,
			  "estado": "EN_CONSULTA",
			  "escalado": false,
			  "fechaEscalamiento": null,
			  "fechaRegistro": \"""" + FECHA_REGISTRO + """
			",
			  "fechaPrimeraConsulta": \"""" + FECHA_PRIMERA_CONSULTA + """
			",
			  "fechaUltimoCambioEstado": \"""" + FECHA_PRIMERA_CONSULTA + """
			",
			  "tiempoEnEstadoSegundos": 0,
			  "tiempoEnEstadoMinutos": 0
			}
			""";

	// ---------------------------------------------------------------- alertas SSE

	public static final String ALERTA_SSE = """
			id:""" + ID_EVENTO_ALERTA + """
			
			event:alerta
			data:{"eventId":\"""" + ID_EVENTO_ALERTA + """
			","pacienteId":\"""" + ID_PACIENTE + """
			","nivel":"CRITICO","nivelNumero":1,"minutosEspera":6,"slaMinutos":5,"fechaRegistro":\"""" + FECHA_REGISTRO + """
			","fechaEscalamiento":\"""" + FECHA_ESCALAMIENTO + """
			","correlationId":\"""" + CORRELATION_ESCALAMIENTO + """
			"}
			""";

	// ---------------------------------------------------------------- reporte RF-05

	public static final String REPORTE = """
			{
			  "desde": \"""" + INICIO_REPORTE + """
			",
			  "hasta": "2026-11-04T03:00:00-05:00",
			  "zonaHoraria": "America/Bogota",
			  "horas": [
			    {
			      "hora": \"""" + INICIO_REPORTE + """
			",
			      "totalRegistrados": 12,
			      "porNivel": { "CRITICO": 1, "URGENTE": 2, "URGENTE_2": 4, "NO_URGENTE": 3, "NO_URGENTE_2": 2 },
			      "pacientesAtendidos": 9,
			      "esperaPromedioMinutos": 18.4,
			      "esperaP95Minutos": 41.0
			    },
			    {
			      "hora": "2026-11-04T01:00:00-05:00",
			      "totalRegistrados": 0,
			      "porNivel": { "CRITICO": 0, "URGENTE": 0, "URGENTE_2": 0, "NO_URGENTE": 0, "NO_URGENTE_2": 0 },
			      "pacientesAtendidos": 0,
			      "esperaPromedioMinutos": null,
			      "esperaP95Minutos": null
			    },
			    {
			      "hora": "2026-11-04T02:00:00-05:00",
			      "totalRegistrados": 3,
			      "porNivel": { "CRITICO": 0, "URGENTE": 1, "URGENTE_2": 1, "NO_URGENTE": 0, "NO_URGENTE_2": 1 },
			      "pacientesAtendidos": 2,
			      "esperaPromedioMinutos": 25.5,
			      "esperaP95Minutos": 33.75
			    }
			  ]
			}
			""";

	// ---------------------------------------------------------------- errores (ProblemDetail, RFC 7807)

	public static final String ERROR_400_VALIDACION = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): frecuenciaCardiaca",
			  "instance": "/triage/pacientes",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			",
			  "errores": [
			    { "campo": "frecuenciaCardiaca", "valor": 300, "mensaje": "valor invalido; esperado: 20 a 250 bpm" }
			  ]
			}
			""";

	public static final String ERROR_400_REPORTE = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): desde",
			  "instance": "/triage/reportes/operacion",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			",
			  "errores": [
			    { "campo": "desde", "valor": "2026-11-05T00:00Z", "mensaje": "debe ser anterior a 'hasta' (rango [desde, hasta))" }
			  ]
			}
			""";

	public static final String ERROR_401 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			no-autenticado",
			  "title": "No autenticado",
			  "status": 401,
			  "detail": "Falta el header X-API-Key o su valor no es valido",
			  "instance": "/triage/pacientes",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			"
			}
			""";

	public static final String ERROR_404 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			no-encontrado",
			  "title": "Paciente no encontrado",
			  "status": 404,
			  "detail": "No existe un paciente con id\s""" + ID_PACIENTE + """
			",
			  "instance": "/triage/pacientes/""" + ID_PACIENTE + """
			",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			"
			}
			""";

	public static final String ERROR_409_TRANSICION = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			transicion-invalida",
			  "title": "Transicion de estado invalida",
			  "status": 409,
			  "detail": "No se puede pasar de ALTA a EN_CONSULTA. Destinos validos desde ALTA: ninguno",
			  "instance": "/triage/pacientes/""" + ID_PACIENTE + """
			/estado",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			",
			  "estadoActual": "ALTA",
			  "estadoSolicitado": "EN_CONSULTA"
			}
			""";

	public static final String ERROR_500 = """
			{
			  "type": \"""" + ErrorApi.URI_BASE + """
			interno",
			  "title": "Error interno",
			  "status": 500,
			  "detail": "Ocurrio un error interno. Reporta el correlationId al equipo de soporte.",
			  "instance": "/triage/pacientes",
			  "correlationId": \"""" + CORRELATION_ID + """
			",
			  "timestamp": \"""" + FECHA_REGISTRO + """
			"
			}
			""";

}
