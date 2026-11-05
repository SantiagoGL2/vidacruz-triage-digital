# Observabilidad

Qué se puede ver de los dos servicios y cómo: salud, logs con correlación, métricas y **trazas distribuidas** (el bonus del
enunciado). Los tres pilares comparten el mismo hilo conductor: `correlationId` (logs y eventos) y `traceId` (trazas y logs).

## 1. Salud (probes)

| Endpoint (públicos) | Qué dice |
|---|---|
| `/actuator/health/liveness` | el proceso está vivo; no depende de ninguna dependencia externa |
| `/actuator/health/readiness` | puede recibir tráfico. triage: R2DBC. asistente: Redis y el **índice de protocolos** (`INDEXANDO` / `LISTO` / `FALLIDO`) |

Con `X-API-Key`, `/actuator/health` añade los detalles (por ejemplo, `indiceProtocolos` con los fragmentos indexados, el error y
la fecha). Un proveedor de IA caído **no** saca al servicio de la rotación una vez indexado (modo degradado: `503` en la
consulta). Kafka no está en la readiness del asistente: si cae, sigue sirviendo la última vista.

## 2. Logs estructurados con correlación

Cada línea es un JSON (formato `logstash` de Spring Boot) con `correlationId`, `traceId` y `spanId` tomados del MDC.

* El cliente puede enviar `X-Correlation-Id` (`[A-Za-z0-9._-]{1,64}`); si falta o no es válido se genera uno. Se devuelve siempre
  en la respuesta, viaja en la cabecera Kafka `correlationId` y en el envelope del evento, y aparece en los logs de **ambos**
  servicios y en el cuerpo de los errores.
* Los logs no llevan datos personales (nombre, edad, queja): solo `id`, `nivel`, `slaMinutos` y `estado`.

```bash
docker compose logs triage-service | grep demo-jaeger-1        # todo lo que pasó con esa petición
```

## 3. Métricas (Micrometer / Prometheus)

`/actuator/prometheus` (requiere `X-API-Key`). Las principales:

| Métrica | Qué mide |
|---|---|
| `triage_pacientes_clasificados_total{nivel}` | clasificación por nivel |
| `triage_pacientes_escalados_total{nivel}` | escalamientos por SLA |
| `triage_outbox_pendientes` · `_publicados_total` · `_fallos_total` | salud del publicador del outbox |
| `triage_alertas_emitidas_total{canal}` · `_fallos_total{canal}` | alertas SSE y webhook |
| `asistente_llm_latencia_seconds{operacion,resultado}` · `asistente_llm_tokens_total{tipo}` | latencia y consumo del proveedor de IA |
| `asistente_consultas_total{resultado}` · `asistente_citas_descartadas_total` | consultas RAG y citas rechazadas por la barrera 3 |
| `asistente_eventos_procesados_total{eventType}` · `_duplicados` · `_ignorados` · `_dlt` | proyección de eventos |
| `asistente_pacientes_activos{nivel}` | vista de urgencias como gauge |

Además `/actuator/circuitbreakers` (asistente) muestra el estado del circuit breaker del LLM.

```bash
curl -s localhost:8081/actuator/prometheus -H "X-API-Key: $TRIAGE_API_KEY"    | grep -E '^triage_(pacientes|outbox|alertas)'
curl -s localhost:8082/actuator/prometheus -H "X-API-Key: $ASSISTANT_API_KEY" | grep -E '^asistente_(consultas|llm_latencia|pacientes_activos)'
```

## 4. Trazas distribuidas (OpenTelemetry)

Una petición que registra un paciente cruza dos servicios y un broker, y la mitad del camino es asíncrona (el outbox publica
después, desde otro hilo). Con las trazas activadas, todo queda en **una sola traza**:

```
[triage]    http post /triage/pacientes                 ← petición HTTP (WebFlux)
  [triage]    security filterchain / authorize exchange ← Spring Security
  [triage]    query × N                                  ← cada consulta SQL de R2DBC (INSERT paciente, auditoría, outbox…)
  [triage]    outbox publicar                            ← el publicador, hijo del traceparent guardado en el outbox
    [triage]    triaje.eventos send                      ← KafkaTemplate; inyecta la cabecera traceparent en el mensaje
      [clinical-assistant] triaje.eventos receive        ← el consumidor continúa la misma traza
        [clinical-assistant] eval / evalsha              ← comandos Redis (el script Lua de la proyección)
```

### Cómo se propaga

* **HTTP → R2DBC:** la observación de la petición de WebFlux es el padre; `r2dbc-proxy` (`ObservacionR2dbcConfig`) emite un span
  por consulta (el texto SQL va en el span; los valores de los parámetros **no**, pueden ser datos clínicos).
* **Petición → outbox → Kafka (el salto asíncrono):** al guardar el evento en `outbox_eventos` se guarda también el
  `traceparent` de la petición (columna `traceparent`, migración Flyway `V4`). El publicador, que corre en otro hilo y a otra
  hora, lo restaura como padre de un span `outbox publicar` (`ContextoTraza`) y el `KafkaTemplate`
  (`spring.kafka.template.observation-enabled`) añade su propio `traceparent` a la cabecera del mensaje.
* **Kafka → asistente → Redis:** el listener container (`spring.kafka.listener.observation-enabled`) continúa la traza desde la
  cabecera, y los comandos de Lettuce (`ObservacionRedisConfig`, `MicrometerTracing`) cuelgan del span de consumo.
* **Formato:** W3C `traceparent`. El propagador está declarado explícitamente (`PropagacionTrazasConfig`): sin eso, en este
  proyecto Spring Boot dejaba un propagador vacío (`NoopTextMapPropagator`) y no se enviaba ninguna cabecera; lo detectó
  `TrazaDistribuidaTests`.
* **Calentamiento:** `CalentamientoTrazas` crea un span al arrancar. La primera creación de un span inicializa OpenTelemetry
  leyendo jars del classpath (bloqueante); hecho en el primer request habría bloqueado un hilo del event loop (BlockHound lo
  detectó).

### Verla en Jaeger

```bash
OTEL_EXPORT_ENABLED=true docker compose --profile observabilidad up --build -d      # Jaeger all-in-one + los servicios exportando
# (PowerShell:  $env:OTEL_EXPORT_ENABLED="true"; docker compose --profile observabilidad up --build -d)
```

1. Registra un paciente (ver el `curl` del README) con `-H "X-Correlation-Id: demo-1"`.
2. Abre <http://localhost:16686>, **Service** = `triage-service`, **Operation** = `http post /triage/pacientes` → *Find Traces*.
3. La traza tiene spans de **dos servicios** (`triage-service` y `clinical-assistant-service`); el árbol es el del esquema de arriba.
4. El mismo `traceId` aparece en los logs (`"traceId":"…"`) para saltar de la traza al log.

Por API (útil en scripts): `GET http://localhost:16686/api/traces?service=triage-service&operation=http%20post%20/triage/pacientes&limit=5`.

Verificación hecha (4-nov-2026): un solo trace con **17 spans** entre `triage-service` (HTTP, seguridad, 6 consultas SQL,
`outbox publicar`, envío y recepción de Kafka) y `clinical-assistant-service` (consumo + `eval`/`evalsha` de Redis).

### Variables

| Variable | Por defecto | Qué hace |
|---|---|---|
| `OTEL_EXPORT_ENABLED` | `false` | exporta las trazas por OTLP/HTTP (los spans se crean siempre; sin exportar no salen del proceso) |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` (`http://jaeger:4318/v1/traces` dentro de compose) | destino OTLP |
| `TRACING_SAMPLING_PROBABILITY` | `1.0` | fracción de peticiones trazadas; en producción bajar (p. ej. `0.1`) |

### Pruebas

`TrazaDistribuidaTests` en cada servicio, con un `InMemorySpanExporter`:

* **triage:** la petición, el outbox y la publicación comparten un traceId; el mensaje de Kafka lleva `traceparent`; el span
  `outbox publicar` es hijo del span HTTP cuyo `traceparent` se guardó.
* **asistente:** un mensaje con `traceparent` produce un span `CONSUMER` hijo de ese span y comandos `eval`/`evalsha` hijos del consumo.

### Límites

* Los eventos que **no nacen de una petición** (el escalamiento por SLA lo dispara un proceso de fondo) no tienen traza de
  origen: su traza empieza en `outbox publicar` sin padre.
* El vector store de Redis (Jedis, RAG) y las llamadas HTTP al proveedor de IA no se trazan; las métricas
  `asistente_llm_latencia_seconds` cubren la latencia del LLM.
* En Azure el exportador está apagado: habría que desplegar un colector OTLP (Jaeger, Grafana Tempo o Application Insights con
  OTLP) y fijar `OTEL_EXPORT_ENABLED` y el endpoint en los `ConfigMap`. Jaeger de compose es en memoria: reiniciarlo borra las trazas.
