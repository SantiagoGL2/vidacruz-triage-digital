# ADR 0006 — Sin Saga: consistencia eventual con Transactional Outbox

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** `triage-service` y `clinical-assistant-service`

## Contexto

Hay dos servicios con datos propios (PostgreSQL en triage, Redis en el asistente) que se integran por Kafka. La pregunta es si
alguna operación de negocio abarca **a la vez** a los dos y necesita una transacción distribuida o una Saga (secuencia de
transacciones locales con compensaciones).

Las operaciones de negocio son:

- **Registrar / cambiar de estado / escalar un paciente:** ocurren **solo** en triage. Cambio de estado + auditoría + evento
  se confirman en **una única transacción local** de PostgreSQL (Transactional Outbox).
- **Estado de urgencias:** el asistente solo **proyecta** los eventos en una vista de lectura. No decide nada que triage deba
  deshacer y no escribe de vuelta en triage.
- **Consulta de protocolos (RAG):** no modifica estado de negocio; no participa de ningún flujo transaccional.
- **Alertas (RF-06):** consumen el evento `paciente_escalado`; si un canal falla no hay nada que compensar (se reintenta o va a la DLT).

## Decisión

**No se implementa una Saga ni transacciones distribuidas (2PC).** La integración es por **eventos con consistencia eventual**:

1. **Transactional Outbox** en triage: el evento se guarda en la misma transacción que el cambio de estado; un publicador
   reactivo lo envía a Kafka (`FOR UPDATE SKIP LOCKED`, reintentos con backoff). Si Kafka está caído el registro no falla; el
   evento sale cuando vuelva (entrega *al menos una vez*).
2. **Consumidores idempotentes** (deduplicación por `eventId`; en el asistente, atómica dentro del script Lua que aplica el
   evento) para que una reentrega no duplique el efecto.
3. **Orden por paciente** con la clave del mensaje = id del paciente (misma partición).
4. **Errores:** reintento con backoff para lo transitorio y `triaje.eventos.DLT` para lo no recuperable (ADR 0004).
5. **El triage nunca depende del asistente:** si el asistente o su Redis caen, el registro, las transiciones y el escalamiento
   siguen; la vista se pone al día al recuperarse (reconstruye desde el topic).

## Alternativas consideradas

- **Saga coreografiada u orquestada:** tiene sentido cuando una operación necesita que varios servicios confirmen o se
  *deshagan* (reserva de stock + cobro + envío). Aquí no hay ningún paso que el asistente pueda rechazar y que obligue a
  compensar en triage; añadiría estados intermedios, un orquestador y compensaciones sin ningún caso que las use.
- **2PC / XA entre PostgreSQL y Redis/Kafka:** acopla la disponibilidad de triage a la del asistente, que es justo lo que el
  enunciado prohíbe, y Redis/Kafka no lo ofrecen de forma práctica.
- **Escribir en Kafka directamente en la transacción de negocio (sin outbox):** deja una ventana en la que la base confirma y
  el evento se pierde (o al revés). El outbox la cierra.

## Consecuencias

- La vista de urgencias es **eventualmente consistente** (unos cientos de milisegundos en condiciones normales; más si
  Kafka o Redis estuvieron caídos). Es aceptable para un tablero; no lo sería para una decisión clínica, que se toma con el
  `GET /triage/pacientes/{id}` de triage (fuente de verdad).
- Al menos una vez + idempotencia = efecto exactamente una vez **dentro de la ventana de deduplicación** (7 días en Redis,
  supuesto 86; la alerta de triage deduplica en memoria, supuesto 69).
- **Cuándo revisarlo:** si el asistente pasara a escribir de vuelta en triage (p. ej. «confirmar lectura de protocolo» que
  cambie el estado del paciente) o si apareciera un tercer servicio que deba confirmar una operación, habría que modelar
  una Saga con compensaciones explícitas.
