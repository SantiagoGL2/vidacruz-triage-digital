# VidaCruz Triage Digital

Dos microservicios reactivos para las urgencias de VidaCruz:

* **`triage-service`** — registra pacientes con sus signos vitales, los clasifica por reglas objetivas (RN-01), vigila el SLA por nivel,
  escala automáticamente (RF-03), audita todo (RN-04), reporta la operación por hora con SQL avanzado (RF-05), alerta al equipo médico
  (RF-06) y publica eventos a Kafka con *Transactional Outbox*.
* **`clinical-assistant-service`** — responde preguntas sobre protocolos clínicos (RAG con cita obligatoria) y mantiene en Redis el
  estado de urgencias a partir de los eventos Kafka.

## Arrancar

```bash
cp .env.example .env                     # primera vez: poner DB_PASSWORD y TRIAGE_API_KEY
docker compose up -d postgres kafka kafka-init redis
cd triage-service && ./gradlew bootRun   # perfil "local": SLA de 1-5 min y evaluación cada 5 s
./gradlew test                           # requiere Docker (Testcontainers)
```

Toda la plataforma: `docker compose up --build`. Detalle de comandos y convenciones en [`CLAUDE.md`](CLAUDE.md).

## API de triage (Swagger UI)

* **Swagger UI:** <http://localhost:8081/swagger-ui.html> (con `docker compose` el servicio publica el puerto `8081`; con `bootRun`, `SERVER_PORT`, por defecto `8080`).
* **Contrato OpenAPI:** `/v3/api-docs`.
* Los endpoints de negocio exigen el header `X-API-Key`: en Swagger UI pulsa **Authorize** y pega el valor de `TRIAGE_API_KEY`.
  La documentación se apaga con `SWAGGER_HABILITADO=false` (recomendado en producción).

| Endpoint | Requerimiento |
|----------|---------------|
| `POST /triage/pacientes` | RF-01 — registrar y clasificar |
| `PATCH /triage/pacientes/{id}/estado` | RF-02 — flujo de estados |
| `GET /triage/pacientes/{id}` | RF-02 — consulta |
| `GET /triage/alertas/stream` | RF-06 — alertas SSE ([ADR 0002](docs/adr/0002-alertas-de-escalamiento-sse-y-webhook.md)) |
| `GET /triage/reportes/operacion?desde&hasta` | RF-05 — [reporte por hora](docs/reporte-operacion.md) |

## API del asistente (estado de urgencias)

* **Swagger UI:** <http://localhost:8082/swagger-ui.html> (contrato en `/v3/api-docs`); mismo botón **Authorize** con `ASSISTANT_API_KEY`.
* `GET /asistente/estado-urgencias` — pacientes activos por nivel y escalados, reconstruidos desde `triaje.eventos` (RF-04 parte B, [ADR 0003](docs/adr/0003-envelope-de-eventos-duplicado-entre-servicios.md)).
* `./gradlew bootRun` en `clinical-assistant-service` lee el `.env` de la raíz (perfil `local`).

### Consulta de protocolos con IA (RAG)

`POST /asistente/consulta` `{ "pregunta": "..." }` → `{ "respuesta": "...", "fuentes": [ { "protocolo": "PROTO-001", "seccion": "Medidas iniciales" } ] }`.
Siempre con fuente del corpus (RN-05); si no hay protocolo relevante responde el aviso de «no existe protocolo disponible» con `fuentes: []`, sin llamar al LLM.
Flujo, prompt, umbral y resiliencia: [`docs/asistente-rag.md`](docs/asistente-rag.md) · vector store: [ADR 0005](docs/adr/0005-redis-stack-como-vector-store-y-fragmentacion-por-seccion.md).

* **Proveedor (por defecto):** cualquier API compatible con OpenAI. Modelo de chat `gpt-4o-mini` y embeddings `text-embedding-3-small` (1536 dimensiones), temperatura 0.
  Se cambia con variables de entorno / `.env`: `LLM_API_KEY` (obligatoria, nunca se versiona), `LLM_BASE_URL` (OpenAI, Azure OpenAI vía gateway, Groq, vLLM, Ollama…),
  `LLM_CHAT_MODEL` y `LLM_EMBEDDING_MODEL`. Si cambias el modelo de embeddings: recalibra `ASSISTANT_RAG_SIMILARITY_THRESHOLD` (hoy 0.75, medido con `text-embedding-3-small`; tabla en `docs/asistente-rag.md`). Un cambio de modelo reindexa solo; si cambia la dimensión de los vectores, borra además el índice
  (`FT.DROPINDEX asistente-protocolos-idx DD` en Redis). Validado contra OpenAI real (`gpt-4o-mini` + `text-embedding-3-small`) además de con un proveedor simulado en los tests.
* **Agregar un protocolo:** suelta un archivo `PROTO-NNN.md` en `clinical-assistant-service/src/main/resources/protocolos/` con el encabezado
  (`---` / `id: PROTO-NNN` / `titulo: …` / `version: 1.0` / `---`) y una sección `## Apartado` por apartado, y reinicia el servicio: se indexa solo (idempotente).
  También se puede apuntar a un directorio externo con `ASSISTANT_RAG_CORPUS=file:/ruta/*.md`.
* **Probar contra el proveedor real (opcional):** `LLM_API_KEY=sk-... ./gradlew test --tests '*ProveedorRealTests' -i` desde `clinical-assistant-service`
  (necesita Docker; ejecuta las 3 consultas de ejemplo y verifica las fuentes; también imprime las similitudes para calibrar el umbral). Sin `LLM_API_KEY` se omite.
* **Modo degradado:** si el proveedor no responde, `503` con «El asistente clínico no está disponible temporalmente; consulte los protocolos impresos». El resto del servicio
  (`/asistente/estado-urgencias`) y la readiness no se ven afectados.

## Seguridad

Los dos servicios se protegen con una **API key por servicio** en el header `X-API-Key` (decisión y límites en [ADR 0007](docs/adr/0007-autenticacion-por-api-key.md)). La clave sale de variables de entorno (`TRIAGE_API_KEY`, `ASSISTANT_API_KEY`; ver `.env.example`), nunca está en el código ni tiene valor por defecto.

* **Públicas:** solo `/actuator/health/**` y la documentación OpenAPI (`/v3/api-docs`, `/swagger-ui.html`; se apaga con `SWAGGER_HABILITADO=false`). Todo lo demás exige la clave.
* **Con `curl`:**

  ```bash
  # triage (puerto 8081 con docker compose)
  curl -H "X-API-Key: $TRIAGE_API_KEY" http://localhost:8081/triage/pacientes/<id>

  # asistente (puerto 8082)
  curl -H "X-API-Key: $ASSISTANT_API_KEY" http://localhost:8082/asistente/estado-urgencias

  # sin clave o con una incorrecta: 401 application/problem+json
  curl -i http://localhost:8081/triage/pacientes/<id>
  ```

* **Con Swagger UI:** abre `/swagger-ui.html` del servicio, pulsa **Authorize**, pega el valor de `TRIAGE_API_KEY` (o `ASSISTANT_API_KEY`) y confirma; las pruebas desde la página ya llevan el header.
* **Límites:** la clave identifica a la aplicación, no a la persona (la auditoría no registra usuario), no hay roles y la rotación es manual. En producción se propone Entra ID con roles por perfil clínico y claves en Azure Key Vault (ver el ADR).

## Documentación

* [`docs/enunciado.md`](docs/enunciado.md) — requerimientos y reglas de negocio.
* [`docs/supuestos.md`](docs/supuestos.md) — decisiones y supuestos.
* [`docs/eventos.md`](docs/eventos.md) — contrato de los eventos Kafka (`triaje.eventos`).
* [`docs/asistente-rag.md`](docs/asistente-rag.md) — el RAG del asistente: barreras anti-alucinación, prompt, umbral, resiliencia.
* [`docs/errores.md`](docs/errores.md) — catálogo de errores de la API (`type`, status, mensajes).
* [`docs/reporte-operacion.md`](docs/reporte-operacion.md) — la consulta SQL del RF-05 explicada, supuestos y EXPLAIN.
* [`docs/adr/`](docs/adr/) — decisiones de arquitectura.
