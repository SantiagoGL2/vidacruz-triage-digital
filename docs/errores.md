# Catálogo de errores de la API

Ambos servicios responden los errores con **RFC 7807** (`application/problem+json`) y el mismo cuerpo:

```json
{
  "type": "https://vidacruz.example/errores/transicion-invalida",
  "title": "Transicion de estado invalida",
  "status": 409,
  "detail": "No se puede pasar de ALTA a EN_CONSULTA. Destinos validos desde ALTA: ninguno",
  "instance": "/triage/pacientes/3f1c…/estado",
  "correlationId": "…",
  "timestamp": "2026-11-04T15:20:31.511Z"
}
```

* `type` es **estable**: los clientes deben reconocer el error por él (o por el `status`), nunca por `title`/`detail`, que son texto para personas y pueden cambiar de idioma. La URI es un identificador, no necesita resolverse.
* Dónde vive cada cosa: el catálogo (status + `type`) es el enum `ErrorApi` de cada servicio (`infrastructure/entrypoint/web/exception`); `title` y `detail` están en `src/main/resources/messages.properties` (claves `error.<codigo>.titulo` y `error.<codigo>.mensaje`), se leen con `MessageSource` y respetan `Accept-Language`. Hoy solo existe el español (idioma por defecto); para otro idioma se añade `messages_<idioma>.properties` con las mismas claves.
* Los errores genéricos del framework (404 de una ruta, 405, 415…) conservan su status y usan `type = https://vidacruz.example/errores/http-<status>`.
* Un 500 nunca incluye la excepción ni el stacktrace: van al log con el `correlationId`.

## triage-service

| `type` (tras `https://vidacruz.example/errores/`) | Status | Cuándo | Extras en el cuerpo |
|---|---|---|---|
| `validacion` | 400 | Dato clínico fuera de rango, campo obligatorio ausente, tipo/enum/UUID inválido, rango de reporte inválido | `errores[]: {campo, valor, mensaje}` |
| `json-malformado` | 400 | El cuerpo no es JSON | |
| `solicitud-invalida` | 400 | Cuerpo ausente o ilegible | |
| `no-autenticado` | 401 | Falta `X-API-Key` o es incorrecta | |
| `no-encontrado` | 404 | El paciente no existe | |
| `transicion-invalida` | 409 | Transición de estado no permitida (o ya está en ese estado) | `estadoActual`, `estadoSolicitado` |
| `conflicto-concurrencia` | 409 | Otra operación modificó al paciente a la vez; reintentar | |
| `interno` | 500 | Error inesperado | |

## clinical-assistant-service

| `type` (tras `https://vidacruz.example/errores/`) | Status | Cuándo | Extras |
|---|---|---|---|
| `validacion` | 400 | Pregunta vacía o fuera de 5–500 caracteres, tipo inválido | `errores[]` |
| `json-malformado` | 400 | El cuerpo no es JSON | |
| `solicitud-invalida` | 400 | Cuerpo ausente o ilegible | |
| `no-autenticado` | 401 | Falta `X-API-Key` o es incorrecta | |
| `demasiadas-peticiones` | 429 | Se superó el límite de consultas por API key (20 por minuto por defecto); cada consulta tiene costo en el proveedor de IA | cabecera `Retry-After` |
| `asistente-no-disponible` | 503 | El proveedor de IA no responde (RN-05: no se inventa respuesta) | `operacion`, cabecera `Retry-After` |
| `indice-no-disponible` | 503 | Los protocolos aún se están indexando | `Retry-After` |
| `estado-no-disponible` | 503 | Redis no responde | `Retry-After` |
| `interno` | 500 | Error inesperado | |

## Qué texto sale de dónde

* **Catálogo + `messages.properties`:** `title`, `detail` de cada error, y los mensajes por campo generados por el handler (`campo.*`, `tipo.*`) y por Bean Validation (`consulta.pregunta.*`, con `{clave}` en la anotación).
* **Pasa tal cual desde la excepción:** el rango esperado de un dato clínico (`DatoClinicoInvalidoException`) y el motivo de un rango de reporte inválido (`ParametroReporteInvalidoException`); son mensajes del dominio/aplicación.
