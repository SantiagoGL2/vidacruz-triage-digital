package com.vidacruz.assistant.infrastructure.drivenadapter.observability;

/** Nombres de las metricas Micrometer de clinical-assistant-service y sus tags (los consultan dashboards y alertas: no cambiarlos a la ligera). */
public final class NombresMetricas {

	public static final String PACIENTES_ACTIVOS = "asistente.pacientes.activos";
	public static final String PACIENTES_ESCALADOS = "asistente.pacientes.escalados";
	public static final String EVENTOS_PROCESADOS = "asistente.eventos.procesados";
	public static final String EVENTOS_DUPLICADOS = "asistente.eventos.duplicados";
	public static final String EVENTOS_IGNORADOS = "asistente.eventos.ignorados";
	public static final String EVENTOS_DLT = "asistente.eventos.dlt";
	public static final String CONSULTAS = "asistente.consultas";
	public static final String CITAS_DESCARTADAS = "asistente.citas.descartadas";
	public static final String LLM_LATENCIA = "asistente.llm.latencia";
	public static final String LLM_TOKENS = "asistente.llm.tokens";

	public static final String TAG_NIVEL = "nivel";
	public static final String TAG_EVENT_TYPE = "eventType";
	public static final String TAG_MOTIVO = "motivo";
	public static final String TAG_RESULTADO = "resultado";
	public static final String TAG_OPERACION = "operacion";
	public static final String TAG_TIPO = "tipo";

	private NombresMetricas() {
	}

}
