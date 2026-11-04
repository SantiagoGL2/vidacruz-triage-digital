# Eventos de triage (`triaje.eventos`)

Contrato de los eventos que `triage-service` deja en la tabla `outbox_eventos` y (en el siguiente paso) publica en Kafka.
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
| `eventType` | string | `paciente_registrado`, `paciente_en_consulta`, `paciente_alta` (y `paciente_escalado`, que emitira el scheduler de RF-03). Minusculas con guion bajo. |
| `occurredAt` | ISO 8601 UTC | Instante del cambio de negocio (no el de publicacion). Es el mismo de la fila de auditoria. |
| `version` | int | Version del esquema del envelope (`triage.kafka.event-version`, hoy `1`). Cambios incompatibles suben este numero. |
| `correlationId` | string | `X-Correlation-Id` de la peticion HTTP que origino el cambio (o el generado por el servicio). `sin-correlacion` si el cambio no nacio de una peticion. |
| `payload` | objeto | Ver abajo. |

**Clave del mensaje Kafka** = `payload.pacienteId` (garantiza orden por paciente). En la tabla es `outbox_eventos.aggregate_id`.

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

## Garantias

- **Atomicidad:** el cambio de estado, su fila de auditoria y la fila del outbox se escriben en una sola transaccion R2DBC; si falla cualquier paso no queda nada.
- **Entrega al menos una vez:** el publicador (siguiente paso) marca `published_at` tras el ack de Kafka; un reintento puede duplicar un evento, por eso el consumidor deduplica por `eventId`.
- **Orden:** garantizado por paciente (misma clave de particion), no entre pacientes.
