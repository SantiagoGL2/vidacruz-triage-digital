# Eventos de triage (`triaje.eventos`)

Contrato de los eventos que `triage-service` deja en la tabla `outbox_eventos` y el publicador del outbox envia a Kafka.
Los consume `clinical-assistant-service` para mantener el estado de urgencias en Redis **sin llamar a la API de triage**.

## Envelope

```json
{
  "eventId": "5b0f3c1e-8f6d-4f55-9a0e-2d2f4b5f0a11",
  "eventType": "paciente_registrado",
  "occurredAt": "2026-11-04T15:17:57.285937Z",
  "version": 1,
  "correlationId": "demo-001",
  "payload": {
    "pacienteId": "e62f5bb0-848c-4343-9887-19cc8fcf3a0a",
    "nivel": "CRITICO",
    "nivelNumero": 1,
    "estadoAnterior": null,
    "estadoNuevo": "REGISTRADO",
    "fechaRegistro": "2026-11-04T15:17:57.285937Z",
    "slaMinutos": 5,
    "escalado": false,
    "fechaPrimeraConsulta": null,
    "fechaCambioEstado": "2026-11-04T15:17:57.285937Z"
  }
}
```

| Campo | Tipo | Descripcion |
|-------|------|-------------|
| `eventId` | UUID | Identificador unico del evento. **Es el mismo `id` de la fila en `outbox_eventos`.** El consumidor lo usa para deduplicar (idempotencia). |
| `eventType` | string | `paciente_registrado`, `paciente_en_consulta`, `paciente_alta` y `paciente_escalado` (lo emite el proceso de escalamiento por SLA, RF-03). Minusculas con guion bajo. |
| `occurredAt` | ISO 8601 UTC | Instante del cambio de negocio (no el de publicacion). Es el mismo de la fila de auditoria. |
| `version` | int | Version del esquema del envelope (`triage.kafka.event-version`, hoy `1`). Cambios incompatibles suben este numero. |
| `correlationId` | string | `X-Correlation-Id` de la peticion HTTP que origino el cambio (o el generado por el servicio). `sin-correlacion` si el cambio no nacio de una peticion. |
| `payload` | objeto | Ver abajo. |

## Mensaje Kafka

Topic `triaje.eventos` (DLT: `triaje.eventos.DLT`).

| Parte | Valor |
|-------|-------|
| **Key** | `payload.pacienteId` (garantiza orden por paciente dentro de la particion). En la tabla es `outbox_eventos.aggregate_id`. |
| **Value** | el envelope JSON completo, tal como esta en `outbox_eventos.payload` (no se vuelve a serializar). |
| **Header `eventId`** | el mismo `eventId` del envelope (permite deduplicar sin parsear el cuerpo). |
| **Header `eventType`** | el mismo `eventType` (permite filtrar sin parsear el cuerpo). |
| **Header `correlationId`** | el `correlationId` del envelope (vacio si no lo hay). |

El productor es idempotente (`acks=all`, `enable.idempotence=true`).

## Payload

| Campo | Tipo | Descripcion |
|-------|------|-------------|
| `pacienteId` | UUID | Paciente afectado. |
| `nivel` | string | `CRITICO`, `URGENTE`, `URGENTE_2`, `NO_URGENTE`, `NO_URGENTE_2`. |
| `nivelNumero` | int 1..5 | Equivalente numerico (1 = mas grave). |
| `estadoAnterior` | string \| null | `null` solo en `paciente_registrado`. |
| `estadoNuevo` | string | `REGISTRADO`, `EN_CONSULTA`, `ALTA`. |
| `fechaRegistro` | ISO 8601 | Cuando se registro el paciente. |
| `slaMinutos` | int | SLA congelado al registrar (RN-02). |
| `escalado` | boolean | Flag persistente de escalamiento al momento del evento. |
| `fechaPrimeraConsulta` | ISO 8601 \| null | `null` mientras no haya entrado a consulta (y en alta directa). |
| `fechaCambioEstado` | ISO 8601 | `fechaUltimoCambioEstado` del paciente tras el evento. |

No incluye datos personales (nombre, edad, queja ni signos vitales): el asistente no los necesita y asi no salen del servicio de triage.

## Como reconstruir el conteo de activos por nivel

Un paciente esta **activo** mientras su estado no sea `ALTA` (supuesto: `REGISTRADO` y `EN_CONSULTA` cuentan como activos).
El consumidor mantiene un contador por `nivel` aplicando cada evento una sola vez (dedupe por `eventId`):

| `eventType` | Efecto sobre el conteo |
|-------------|------------------------|
| `paciente_registrado` | `activos[nivel] += 1` |
| `paciente_en_consulta` | sin cambio de activos (puede mover el paciente de «en espera» a «en consulta» si se lleva ese desglose) |
| `paciente_alta` | `activos[nivel] -= 1` |
| `paciente_escalado` | sin cambio de activos; incrementa el contador de escalados |

Como el payload trae `estadoAnterior` y `estadoNuevo`, el consumidor puede derivar tambien el desglose por estado sin consultar la API.

## paciente_escalado (RF-03, RN-03)

Lo emite el proceso de escalamiento cuando un paciente `REGISTRADO` supera su SLA. **No cambia el estado**:
`estadoAnterior = estadoNuevo = "REGISTRADO"` y `escalado = true`; `fechaCambioEstado` sigue siendo la del ultimo cambio
de estado real (el escalamiento no reinicia el tiempo en estado). `occurredAt` es el instante del escalamiento y su
`correlationId` es el del ciclo de fondo (`escalamiento-sla-xxxxxxxx`). Se emite **a lo sumo una vez por paciente**.
Para el conteo de activos no tiene efecto (ver tabla anterior).

## Consumidores

| Consumidor | Grupo | Que hace |
|------------|-------|----------|
| `clinical-assistant-service` (estado de urgencias, RF-04 B) | `asistente-estado-urgencias` | Aplica los 4 tipos a la vista de Redis (ver abajo). Mensajes ilegibles o incompletos → `triaje.eventos.DLT`; `eventType` desconocido → se ignora (no va a la DLT). |
| `triage-service` (alertas, RF-06) | `triage-alertas` | Procesa **solo** `paciente_escalado` y alerta por SSE y webhook (ver ADR 0002). Mensajes ilegibles → `triaje.eventos.DLT` sin reintentos. |

## Garantias

- **Atomicidad:** el cambio de estado, su fila de auditoria y la fila del outbox se escriben en una sola transaccion R2DBC; si falla cualquier paso no queda nada.
- **Entrega al menos una vez:** el publicador marca `published_at` tras el ack de Kafka; un reintento (o una caida entre el ack y el marcado) puede duplicar un evento, por eso el consumidor deduplica por `eventId`.
- **Publicador seguro con varias replicas:** lee el lote con `SELECT ... FOR UPDATE SKIP LOCKED` dentro de una transaccion que dura mientras se publica, y solo entrega el evento **mas antiguo pendiente de cada paciente**, asi que dos replicas no publican el mismo evento ni invierten el orden de un paciente.
- **Kafka caido:** el registro de pacientes no falla. El evento queda pendiente: cada fallo suma un intento y guarda el motivo, y se reintenta con backoff exponencial (`backoff-inicial * 2^(intentos-1)`, tope `backoff-maximo`). Al llegar a `max-intentos` deja de reintentarse y **bloquea los eventos posteriores de ese paciente** (para no desordenar); queda en `outbox_eventos` para revision manual (log `ERROR` y fuera del gauge de pendientes).
- **Metricas:** `triage.outbox.publicados`, `triage.outbox.fallos` (contadores) y `triage.outbox.pendientes` (gauge, se actualiza cada ciclo).
- **Orden:** garantizado por paciente (misma clave de particion), no entre pacientes.

## Qué consume `clinical-assistant-service` (RF-04 parte B)

El asistente reconstruye el estado de urgencias **solo** con este topic (no llama a la API de triage). Usa `eventId`,
`eventType`, `occurredAt` y, del payload, `pacienteId` (siempre) y `nivel` + `fechaRegistro` (solo en
`paciente_registrado`); el resto del payload se ignora. Lee el `correlationId` del header Kafka (o del envelope).
Consulta: `GET /asistente/estado-urgencias` (ver el contrato en su OpenAPI).

| `eventType` | Efecto en la vista |
|-------------|--------------------|
| `paciente_registrado` | el paciente entra (estado `REGISTRADO`) y suma 1 a su nivel; si ya estaba, se ignora |
| `paciente_en_consulta` | pasa a `EN_CONSULTA`; los contadores no cambian |
| `paciente_escalado` | marca `escalado` y suma 1 a `escalados` (una sola vez); los niveles no cambian |
| `paciente_alta` | resta 1 al nivel que tenía en la vista y lo elimina (y baja `escalados` si estaba escalado) |
| otro | se ignora (log + métrica), no va a la DLT |

Requisitos sobre el topic: la clave debe ser el `pacienteId` (orden por paciente) y la **retención debe conservar los
eventos de los pacientes activos** para poder reconstruir la vista desde el inicio (no usar compactación). Más en
`docs/supuestos.md` (82–95) y el [ADR 0003](adr/0003-envelope-de-eventos-duplicado-entre-servicios.md) sobre por qué el
envelope se duplica en cada servicio.
