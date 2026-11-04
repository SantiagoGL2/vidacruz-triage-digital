# ADR 0003 — El envelope de `triaje.eventos` se duplica en cada servicio (sin librería compartida)

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** `triage-service` (productor) y `clinical-assistant-service` (consumidor)

## Contexto

`triage-service` publica los eventos de `triaje.eventos` (envelope `eventId`, `eventType`, `occurredAt`, `version`,
`correlationId`, `payload`; contrato en `docs/eventos.md`) y `clinical-assistant-service` los consume para proyectar el
estado de urgencias en Redis. Ambos servicios son proyectos Gradle **independientes**: cada uno tiene su wrapper, su
`Dockerfile` (con la carpeta del servicio como contexto de build) y se despliega por separado. Hay que decidir cómo
comparten el formato del evento.

## Opciones consideradas

1. **Librería compartida** (módulo Gradle `eventos-contrato` o artefacto publicado). Evita escribir el DTO dos veces,
   pero: obliga a un build multi-módulo o a un repositorio de artefactos que hoy no existe; el contexto de Docker de cada
   servicio dejaría de ser autocontenido; y, sobre todo, **acopla los despliegues**: cambiar la librería obliga a
   recompilar y redesplegar a la vez al productor y a todos los consumidores, que es justo lo que se evita con eventos.
2. **Schema registry** (Avro / JSON Schema con compatibilidad verificada). Es la solución robusta a escala, pero añade
   un componente de infraestructura (y su operación) para un contrato de cuatro eventos.
3. **Duplicar el DTO en cada servicio y tratar `docs/eventos.md` como contrato** (*tolerant reader* + pruebas de
   contrato en ambos lados).

## Decisión

Opción 3. Cada servicio tiene **su propia copia mínima** del envelope y de los vocabularios que necesita (`NivelTriaje`,
tipos de evento), sin dependencia de código entre servicios. La fuente de verdad es `docs/eventos.md`.

Para que la duplicación no se convierta en acoplamiento oculto, el consumidor se escribe como *lector tolerante*:

- **Se ignoran las propiedades desconocidas** del envelope y del payload (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- **El `payload` se lee como árbol JSON** y solo se interpreta cuando el `eventType` es conocido; un evento nuevo de otro
  tipo (con otro payload) no rompe la deserialización.
- **Un `eventType` desconocido se ignora** (log + métrica `asistente.eventos.ignorados{motivo=tipo_desconocido}`), no
  va a la DLT: así triage puede añadir eventos sin romper a los consumidores antiguos.
- El consumidor solo exige lo que necesita (`pacienteId`, y `nivel`/`fechaRegistro` en `paciente_registrado`).
- **Pruebas de contrato en ambos lados:** `triage-service` verifica que lo que publica sigue `docs/eventos.md`
  (`OutboxKafkaTests`), y el asistente tiene `ContratoEventosTests` con JSON de ejemplo tomados de ese documento
  (`src/test/resources/eventos/*.json`) que se deserializan y se aplican por Kafka real.

## Consecuencias

- (+) Los servicios se compilan, prueban y despliegan sin conocerse; un cambio compatible en triage no obliga a
  redesplegar al asistente.
- (+) Sin infraestructura nueva ni build multi-módulo.
- (−) El DTO existe dos veces. Un cambio **incompatible** (renombrar un campo, cambiar un tipo) hay que hacerlo a mano en
  ambos lados; el campo `version` del envelope existe para señalarlo y `docs/eventos.md` debe actualizarse en el mismo
  cambio. Lo detectan las pruebas de contrato si se actualiza un lado y no el otro.
- (−) Si aparecen muchos consumidores o contratos, el coste de mantener copias crece: ese sería el momento de pasar a un
  schema registry (opción 2), sin tocar la lógica de los servicios.
