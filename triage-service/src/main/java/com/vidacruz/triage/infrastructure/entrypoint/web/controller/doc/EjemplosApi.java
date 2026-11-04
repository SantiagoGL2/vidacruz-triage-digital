package com.vidacruz.triage.infrastructure.entrypoint.web.controller.doc;

/**
 * Ejemplos JSON de la documentacion OpenAPI (se referencian desde las anotaciones de los controllers). Los pacientes E1 y E5 son los de
 * "Datos de referencia" del enunciado.
 */
public final class EjemplosApi {

	private EjemplosApi() {
	}

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
			  "id": "e62f5bb0-848c-4343-9887-19cc8fcf3a0a",
			  "nivel": "CRITICO",
			  "nivelNumero": 1,
			  "slaMinutos": 5,
			  "estado": "REGISTRADO",
			  "fechaRegistro": "2026-11-04T15:17:57.285937Z"
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
			  "id": "e62f5bb0-848c-4343-9887-19cc8fcf3a0a",
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
			  "fechaRegistro": "2026-11-04T15:17:57.285937Z",
			  "fechaPrimeraConsulta": "2026-11-04T15:21:10.402118Z",
			  "fechaUltimoCambioEstado": "2026-11-04T15:21:10.402118Z",
			  "tiempoEnEstadoSegundos": 0,
			  "tiempoEnEstadoMinutos": 0
			}
			""";

	// ---------------------------------------------------------------- alertas SSE

	public static final String ALERTA_SSE = """
			id:ac2bf6bf-3a64-4d0e-8d7e-0a8f9d6a7b21
			event:alerta
			data:{"eventId":"ac2bf6bf-3a64-4d0e-8d7e-0a8f9d6a7b21","pacienteId":"e62f5bb0-848c-4343-9887-19cc8fcf3a0a","nivel":"CRITICO","nivelNumero":1,"minutosEspera":6,"slaMinutos":5,"fechaRegistro":"2026-11-04T15:17:57.285937Z","fechaEscalamiento":"2026-11-04T15:23:30.100Z","correlationId":"escalamiento-sla-dc1f9056"}
			""";

	// ---------------------------------------------------------------- reporte RF-05

	public static final String REPORTE = """
			{
			  "desde": "2026-11-04T00:00:00-05:00",
			  "hasta": "2026-11-04T03:00:00-05:00",
			  "zonaHoraria": "America/Bogota",
			  "horas": [
			    {
			      "hora": "2026-11-04T00:00:00-05:00",
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
			  "type": "urn:vidacruz:triage:problema:validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): frecuenciaCardiaca",
			  "instance": "/triage/pacientes",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z",
			  "errores": [
			    { "campo": "frecuenciaCardiaca", "valor": 300, "mensaje": "valor invalido; esperado: 20 a 250 bpm" }
			  ]
			}
			""";

	public static final String ERROR_400_REPORTE = """
			{
			  "type": "urn:vidacruz:triage:problema:validacion",
			  "title": "Datos invalidos",
			  "status": 400,
			  "detail": "Hay 1 dato(s) invalido(s): desde",
			  "instance": "/triage/reportes/operacion",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z",
			  "errores": [
			    { "campo": "desde", "valor": "2026-11-05T00:00Z", "mensaje": "debe ser anterior a 'hasta' (rango [desde, hasta))" }
			  ]
			}
			""";

	public static final String ERROR_401 = """
			{
			  "type": "urn:vidacruz:triage:problema:no-autenticado",
			  "title": "No autenticado",
			  "status": 401,
			  "detail": "Falta el header X-API-Key o su valor no es valido",
			  "instance": "/triage/pacientes",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

	public static final String ERROR_404 = """
			{
			  "type": "urn:vidacruz:triage:problema:no-encontrado",
			  "title": "Paciente no encontrado",
			  "status": 404,
			  "detail": "No existe un paciente con id e62f5bb0-848c-4343-9887-19cc8fcf3a0a",
			  "instance": "/triage/pacientes/e62f5bb0-848c-4343-9887-19cc8fcf3a0a",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

	public static final String ERROR_409_TRANSICION = """
			{
			  "type": "urn:vidacruz:triage:problema:transicion-invalida",
			  "title": "Transicion de estado invalida",
			  "status": 409,
			  "detail": "No se puede pasar de ALTA a EN_CONSULTA. Destinos validos desde ALTA: ninguno",
			  "instance": "/triage/pacientes/e62f5bb0-848c-4343-9887-19cc8fcf3a0a/estado",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z",
			  "estadoActual": "ALTA",
			  "estadoSolicitado": "EN_CONSULTA"
			}
			""";

	public static final String ERROR_500 = """
			{
			  "type": "urn:vidacruz:triage:problema:interno",
			  "title": "Error interno",
			  "status": 500,
			  "detail": "Ocurrio un error interno. Reporta el correlationId al equipo de soporte.",
			  "instance": "/triage/pacientes",
			  "correlationId": "demo-001",
			  "timestamp": "2026-11-04T15:17:57.285937Z"
			}
			""";

}
