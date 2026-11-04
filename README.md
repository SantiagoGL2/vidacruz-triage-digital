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

## Documentación

* [`docs/enunciado.md`](docs/enunciado.md) — requerimientos y reglas de negocio.
* [`docs/supuestos.md`](docs/supuestos.md) — decisiones y supuestos.
* [`docs/eventos.md`](docs/eventos.md) — contrato de los eventos Kafka (`triaje.eventos`).
* [`docs/reporte-operacion.md`](docs/reporte-operacion.md) — la consulta SQL del RF-05 explicada, supuestos y EXPLAIN.
* [`docs/adr/`](docs/adr/) — decisiones de arquitectura.
