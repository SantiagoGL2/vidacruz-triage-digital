package com.vidacruz.triage.infrastructure.drivenadapter.observability;

/** Nombres de las metricas Micrometer de triage-service y sus tags (los consultan dashboards y alertas: no cambiarlos a la ligera). */
public final class NombresMetricas {

	public static final String PACIENTES_CLASIFICADOS = "triage.pacientes.clasificados";
	public static final String PACIENTES_ESCALADOS = "triage.pacientes.escalados";
	public static final String OUTBOX_PUBLICADOS = "triage.outbox.publicados";
	public static final String OUTBOX_FALLOS = "triage.outbox.fallos";
	public static final String OUTBOX_PENDIENTES = "triage.outbox.pendientes";
	public static final String ALERTAS_EMITIDAS = "triage.alertas.emitidas";
	public static final String ALERTAS_FALLOS = "triage.alertas.fallos";

	public static final String TAG_NIVEL = "nivel";
	public static final String TAG_CANAL = "canal";

	public static final String CANAL_SSE = "sse";
	public static final String CANAL_WEBHOOK = "webhook";

	private NombresMetricas() {
	}

}
