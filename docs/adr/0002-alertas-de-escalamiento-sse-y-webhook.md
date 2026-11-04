# ADR 0002 — Alertas de escalamiento (RF-06): consumidor Kafka en triage con canales SSE y webhook

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** `triage-service`

## Contexto

RF-06 exige alertar al equipo médico cuando un paciente es escalado, y deja deliberadamente abiertos el canal, el
formato y la tecnología. El escalamiento ya produce el evento `paciente_escalado` en `triaje.eventos`
(Transactional Outbox → Kafka). Hay que decidir **quién** reacciona a ese evento y **por dónde** se avisa.

Restricciones: el triaje nunca debe depender de la disponibilidad de un canal de aviso; una alerta no debe llevar
datos personales (el nombre del paciente es sensible); un fallo de un canal no debe tapar a los demás; y el sistema
corre con varias réplicas.

## Opciones consideradas

**Dónde se genera la alerta**

1. *Dentro del mismo proceso de escalamiento* (llamar al canal al escalar). Acopla el escalamiento a los canales: si el
   webhook está lento, el UPDATE/transacción espera; y se pierde la alerta si el proceso cae justo después del commit.
2. *Un consumidor del propio topic en `triage-service`* (grupo `triage-alertas`). Desacopla: el escalamiento solo escribe
   el evento (ya garantizado por el outbox) y la alerta es un efecto posterior, reintentable y con DLT.
3. *Que lo haga `clinical-assistant-service`*. Mezcla responsabilidades (el asistente proyecta estado y responde
   consultas) y haría depender las alertas del asistente, que puede estar caído.

**Canal**

- *Push móvil / correo / Slack*: requieren cuentas y credenciales externas que no existen en el entorno de la prueba.
- *Polling de un endpoint*: no es tiempo real y obliga al cliente a decidir el intervalo.
- *WebSocket*: bidireccional, más pesado de lo necesario para un flujo servidor→cliente.
- *SSE (Server-Sent Events)*: HTTP estándar, unidireccional, reconexión automática en el navegador, trivial de probar
  con `curl`, y encaja con WebFlux. 
- *Webhook*: integra con cualquier sistema externo (mensajería del hospital, PagerDuty…) sin que el servicio conozca
  su API.

**Idempotencia del consumidor** (Kafka es *at-least-once*)

- *Tabla en Postgres* con `eventId`: duradera y compartida entre réplicas, pero añade una migración y una escritura
  por evento para algo cuyo efecto es una notificación efímera.
- *Memoria acotada (LRU)*: sin I/O, suficiente aquí.

## Decisión

1. **Consumidor Kafka en `triage-service`** (`entrypoint/kafka`), con su propio consumer group (`triage-alertas`,
   configurable), que lee `triaje.eventos` y procesa **solo** `paciente_escalado`. Llama al caso de uso
   `NotificarEscalamientoUseCase`; no toca adapters. El listener es síncrono por registro (`block(timeout)` en el hilo
   del consumidor, [ADR 0004](0004-consumo-sincrono-de-kafka-en-servicios-reactivos.md)) para que cualquier error llegue al
   reintento/DLT.
2. **Un puerto de salida `NotificadorAlertasPort` con dos adapters**, ambos activos a la vez y aislados entre sí
   (el caso de uso captura el error de cada canal):
   - **SSE** — `GET /triage/alertas/stream` (protegido con `X-API-Key`). Un `Sinks.many().multicast().directBestEffort()`
     reparte cada alerta a todos los clientes conectados, con un comentario *keep-alive* periódico (`heartbeat`).
     Se eligió `directBestEffort` y no `onBackpressureBuffer`: no acumula alertas mientras nadie escucha (un cliente
     nuevo no recibe alertas viejas) y un cliente lento no frena a los demás.
   - **Webhook** — `POST` JSON a `triage.alertas.webhook.url` (vacía = deshabilitado) con `WebClient`, *timeout*,
     *Retry* solo ante fallos transitorios (timeout, conexión, 5xx, 429) y *CircuitBreaker* (Resilience4j). Si falla
     se loguea y se cuenta (`triage.alertas.fallos{canal=webhook}`) pero no bloquea el SSE.
3. **Contenido de la alerta:** `eventId`, `pacienteId`, `nivel`/`nivelNumero`, `minutosEspera`, `slaMinutos`,
   `fechaRegistro`, `fechaEscalamiento` y `correlationId`. **Sin nombre del paciente**: el equipo lo consulta por id
   con `GET /triage/pacientes/{id}`.
4. **Idempotencia por `eventId` en memoria** con caché LRU acotada (`capacidad-deduplicacion`, 10 000 por defecto), a
   través de un puerto (`DeduplicacionEventosPort`) para poder cambiarla por una tabla sin tocar el caso de uso.
5. **Errores:** `DefaultErrorHandler` con backoff exponencial y `DeadLetterPublishingRecoverer` a `triaje.eventos.DLT`.
   Los mensajes ilegibles o incompletos (`EventoInvalidoException`) son no recuperables y van directo a la DLT.
6. El grupo arranca con `auto-offset-reset: earliest` para no perder alertas si el consumidor estuvo caído.

## Consecuencias

- (+) El escalamiento y los canales están desacoplados: Kafka caído o un webhook lento no afectan al triaje.
- (+) Reintentos, DLT y orden por paciente vienen de Kafka; el outbox garantiza que el evento existe aunque el proceso caiga.
- (+) Con SSE el equipo ve las alertas con `curl`/navegador sin credenciales de terceros; con el webhook se integra donde haga falta.
- (−) **SSE es efímero:** una alerta emitida cuando nadie está conectado no se reenvía al conectarse (el estado
  vive en el paciente: `escalado = true`, y en la auditoría/outbox). Un cliente que necesite no perder nada debe usar el
  webhook o consultar los pacientes escalados.
- (−) El navegador (`EventSource`) no puede enviar cabeceras: para el stream desde un navegador hace falta un proxy que
  añada `X-API-Key`, o usar `fetch` con streaming. No se aceptó la API key por query string para no filtrarla en logs.
- (−) La deduplicación en memoria no se comparte entre réplicas ni sobrevive a un reinicio: tras un rebalanceo o un
  reinicio puede repetirse una alerta. Se acepta porque es una notificación, no un efecto de negocio; si dejara de
  serlo, se cambia el adapter por una tabla (el puerto ya existe).
- (−) Por ser *at-least-once*, el webhook puede recibir un duplicado; incluye `eventId` para que el receptor deduplique.
- Con varias réplicas, el grupo reparte las particiones: **cada alerta la emite una sola réplica**, y un cliente SSE solo
  ve las alertas de la réplica a la que está conectado. Con más de una réplica hace falta un balanceador con
  afinidad o un backbone de difusión (Redis pub/sub) — fuera de alcance de esta decisión.
