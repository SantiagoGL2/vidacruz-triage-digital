# Supuestos de diseño

Decisiones tomadas donde el enunciado (`docs/enunciado.md`) es ambiguo. Se amplía con cada paso.

## Dominio de triage (capa `domain/`)

### Clasificación (RN-01)
1. **Queja administrativa — coincidencia por contención.** La queja y las frases de la lista se normalizan (minúsculas, sin tildes vía NFD, espacios colapsados, `trim`). La queja es administrativa si **contiene** alguna frase normalizada. Así «Renovación de recetario» y «renovacion de recetario medico» clasifican igual.
   - *Riesgo conocido:* es una coincidencia de subcadena, no semántica. «Seguimiento de herida infectada» con signos normales saldría como nivel 5. Es aceptable porque nivel 5 solo se alcanza sin ningún criterio de niveles 1-3, y la lista es configurable (se inyecta a `QuejasAdministrativas`).
   - Una frase vacía en la lista se rechaza (haría administrativa toda queja).
2. **Valores enteros para FC, presiones, SpO2, dolor y GCS; `double` para temperatura.** El enunciado define las bandas de SpO2 con enteros (`90–93`); un SpO2 de 93.5 quedaría en un hueco. Al ser entero, `94` no es nivel 2.
3. **Motivos de clasificación.** `Clasificacion` devuelve *todas* las condiciones cumplidas del nivel ganador (p. ej. `FC > 120`, `SpO2 < 90`), no solo la primera. Se guardan en el `detalle` de la auditoría del registro.
4. **Queja nula en el clasificador** se trata como no administrativa (la validación de «queja obligatoria» ocurre antes, en `Paciente`/`RegistradorPacientes`).

### Validaciones (RF-01)
5. **Se acumulan todos los errores.** `DatoClinicoInvalidoException` lleva una lista de `CampoInvalido(campo, valorRecibido, rangoEsperado)`. Los signos vitales validan en su constructor; nombre/edad/queja con `Paciente.validarDatosPersonales`. La capa de aplicación puede combinar ambas listas para responder un único 400 con todos los campos.
6. **Diastólica < sistólica solo se evalúa si ambas son individualmente válidas**, para no duplicar errores (p. ej. sistólica 59 no marca además a la diastólica).
7. **Nombre:** 3–100 caracteres *después* de `trim`; se almacena ya recortado. **Queja:** obligatoria y, además, máximo 500 caracteres (límite de la columna `queja_principal`; el enunciado no define máximo).
8. `temperatura = NaN` es inválida.

### SLA y escalamiento (RN-02, RN-03, RF-03)
9. **«Superó el SLA» = estrictamente después del vencimiento** (`ahora > fechaRegistro + slaMinutos`). Justo en el minuto límite todavía cumple. La consulta SQL del scheduler deberá usar `>` para coincidir.
10. **`PoliticaSla` es una interfaz del dominio**; el SLA se **congela en el paciente** al registrarlo (`slaMinutos`). Cambiar la configuración no altera a pacientes ya registrados.
11. **`escalar(ahora)` devuelve `Optional.empty()` (no lanza)** cuando no aplica (estado ≠ REGISTRADO, ya escalado o SLA no superado). La idempotencia real ante concurrencia la dará el `UPDATE` condicional.
12. **El escalamiento no cambia el estado ni `fechaUltimoCambioEstado`** (el «tiempo en el estado actual» sigue contando desde el último cambio de estado). Solo fija `escalado = true` y `fechaEscalamiento`. En la auditoría `estadoAnterior = estadoNuevo = REGISTRADO`.
13. **Un paciente escalado conserva el flag** al pasar a EN_CONSULTA/ALTA (es histórico, «flag persistente»).

### Máquina de estados (RF-02)
14. **La transición al mismo estado es inválida** (REGISTRADO→REGISTRADO, ALTA→ALTA, …) y lanza `TransicionInvalidaException` (→ 409), con mensaje distinto («ya se encuentra en estado X»).
15. **`fechaPrimeraConsulta`** se fija solo en la primera entrada a EN_CONSULTA. El **alta directa** (REGISTRADO→ALTA) la deja en `null`: no hubo espera hasta consulta y esos pacientes no entran al promedio/p95 del RF-05 (coherente con el CHECK `ck_pacientes_registrado_sin_consulta` y el diseño del esquema).

### Tiempo
16. **Sin `Clock` global:** las operaciones del agregado reciben `Instant ahora`. Los casos de uso (capa de aplicación) inyectarán el `Clock` y lo pasarán.
17. **El `id` lo asigna quien invoca** (`UUID` por parámetro) para que el dominio sea determinista; la capa de aplicación generará el UUID.

## Registro, estados y consulta (application + infrastructure)

### Casos de uso y transacciones
18. **La capa `application/` no usa Spring.** Los casos de uso son clases planas cableadas en `ApplicationConfig`. La transaccion se abstrae con un puerto (`TransaccionPort`) cuya implementacion usa `TransactionalOperator` sobre el `R2dbcTransactionManager`; asi `application/` solo conoce dominio, Reactor y puertos (lo verifica ArchUnit).
19. **Un unico 400 con todos los campos.** El caso de uso combina los errores de datos personales y de signos vitales (supuesto 5). Los campos *ausentes* o de tipo incorrecto los detecta antes la capa web (Bean Validation / Jackson) y se responden en otro 400: no se mezclan con los de rango.
20. **Las metricas y los logs de exito se emiten despues del commit.** Si la transaccion hace rollback, `triage.pacientes.clasificados` no se incrementa.
21. **Los pasos de auditoria y outbox son perezosos** (`Mono.defer`): no se invoca ningun puerto si el `UPDATE` anterior falla.

### Persistencia
22. **`R2dbcEntityTemplate` para lo estandar y `DatabaseClient` para SQL especial.** Los adapters de postgres usan el template (insert explicito, select por id, update con `@Version`) sobre entidades Spring Data R2DBC (`@Table`, `@Id`, `@Column`, `@Version`; nunca JPA). `DatabaseClient` queda reservado para lo que vendra: UPDATE condicional del escalamiento, `FOR UPDATE SKIP LOCKED` y reportes. El alta usa `template.insert(...)` y no `save`, porque el `id` lo asigna la aplicacion y `save` lo trataria como un update.
23. **Optimistic locking con `@Version`:** la entidad lleva la version leida y `template.update(...)` genera `UPDATE ... WHERE id = ? AND version = ?` (+1 al guardar); si no afecta filas lanza `OptimisticLockingFailureException`, que el adapter traduce a `ConflictoConcurrenciaException` (409). En `READ COMMITTED` el segundo `UPDATE` espera al primero y al re-evaluar `version` ya no coincide, asi que de dos cambios simultaneos uno gana y el otro recibe 409. La version viaja por el puerto como `PacienteVersionado` (ver 45).
24. **Temperatura:** la columna es `NUMERIC(3,1)`; al persistir se redondea a 1 decimal (`HALF_UP`). El dominio acepta cualquier `double` en rango, asi que 36.85 se guarda como 36.9. El GET devuelve el valor ya redondeado.
25. **Tiempos truncados a microsegundos** (precision de PostgreSQL) en el `Clock` de la aplicacion (`Clock.tick(systemUTC, 1 us)`), para que lo respondido por el POST coincida con lo que devuelve el GET. Los casos de uso solo llaman a `clock.instant()`.
26. **Auditoria:** `fecha_evento` se escribe explicitamente con el instante del cambio (el mismo del evento), no con `now()` de la base. El adapter solo expone `registrar` (insert); no hay update ni delete y el trigger los rechaza.
27. **Outbox:** `outbox_eventos.payload` guarda el envelope completo como JSONB (codec `Json` de r2dbc-postgresql), el `id` de la fila es el `eventId` y `created_at` es el `occurredAt`. En este paso **no se publica** a Kafka.

### Eventos (ver `docs/eventos.md`)
28. **El evento no lleva datos personales** (nombre, edad, queja, signos): solo lo necesario para el conteo de activos por nivel.
29. **«Activo» = estado distinto de `ALTA`** (REGISTRADO y EN_CONSULTA cuentan). Sin cambio sobre lo que defina el asistente; el payload trae `estadoAnterior`/`estadoNuevo` para cualquier desglose.
30. **Version del envelope** configurable (`triage.kafka.event-version`, hoy 1). Un cambio incompatible sube la version.

### Web
31. **Controllers anotados** (`@RestController` + `@RestControllerAdvice`), no `RouterFunction`.
32. **JSON de entrada plano** y con los nombres del dominio (`nombreCompleto`, `frecuenciaCardiaca`, ...). Los rangos clinicos solo los valida el dominio; los DTO exigen presencia y tipo.
33. **Entrada estricta:** no se aceptan enteros con decimales (72.5), numeros en string (`"72"`) ni numeros/booleanos en campos de texto; todo eso es un 400 con el campo. (`spring.jackson.*` + `JacksonConfig`.) Campos desconocidos se ignoran (default de Boot).
34. **Respuesta del POST:** `id`, `nivel` (nombre), `nivelNumero`, `slaMinutos`, `estado`, `fechaRegistro` (ISO 8601 UTC) mas `Location`. El enunciado dice «nivel clasificado»; se devuelven nombre y numero.
35. **Respuesta del PATCH:** 200 con la misma vista completa que el GET (el tiempo en el estado nuevo es 0 s).
36. **GET:** `tiempoEnEstadoSegundos` y `tiempoEnEstadoMinutos` (este ultimo truncado), calculados con el `Clock` desde `fechaUltimoCambioEstado`. El escalamiento no reinicia ese tiempo (supuesto 12).
37. **Formato de error unico (RFC 7807, `application/problem+json`):** `type` (`urn:vidacruz:triage:problema:<codigo>`), `title`, `status`, `detail`, `instance`, `correlationId`, `timestamp`; el 400 de validacion anade `errores: [{campo, valor, mensaje}]` y el 409 de transicion `estadoActual`/`estadoSolicitado`. El 500 nunca incluye la excepcion ni el stacktrace; van al log con el `correlationId`.
38. **Las rutas inexistentes, 405 y 415** tambien salen como ProblemDetail con su status original.
39. **Un UUID mal formado en la ruta es un 400** (campo `id`), no un 404.

### Seguridad
40. **API key:** header `X-API-Key` (configurable con `triage.security.api-key-header`), valor en `TRIAGE_API_KEY`. Se compara con SHA-256 + `MessageDigest.isEqual` (tiempo constante). Aplica a todo salvo `/actuator/health/**`, incluidos `/actuator/prometheus` y `/actuator/metrics`. Sin key o con key invalida: 401 `ProblemDetail` + `WWW-Authenticate`. No hay sesion ni usuarios.

### Observabilidad
41. **correlationId:** `X-Correlation-Id` se acepta si cumple `[A-Za-z0-9._-]{1,64}`; si no (o si falta) se genera un UUID. Se devuelve siempre en la respuesta, tambien en los 401. Vive en el `Context` de Reactor (no ThreadLocal); `spring.reactor.context-propagation=auto` + un `ThreadLocalAccessor` lo copian al MDC para los logs.
42. **Logs sin datos personales:** los casos de uso loguean `id`, `nivel`, `slaMinutos` y `estado`; nunca nombre, edad ni queja.
43. **Calentamiento de la entrada al arrancar (`CalentamientoEntrada`):** BlockHound detecto dos bloqueos de libreria en frio que ocurririan en el event loop: Hibernate Validator lee sus `ResourceBundle` de disco al interpolar el primer error, y Jackson construye el deserializador de cada DTO bajo un lock interno (dos primeras peticiones concurrentes se bloqueaban entre si). Ambos se provocan una vez en el arranque, en el hilo principal. Residual conocido: la primera peticion invalida con un `Accept-Language` distinto del por defecto carga su bundle en el event loop.

### Tests
44. **BlockHound** vigila los tests de integracion (se instala con `BlockHound.install(integracion)`, no con `builder().install()`, que no carga las integraciones de Reactor). Excepciones puntuales y de libreria: logging sincrono a consola, `UUID.randomUUID` y carga perezosa de clases. `BlockHoundTests` comprueba que el detector esta realmente activo.

### Estructura de paquetes (refactor)
45. **`PacienteVersionado` vive en `domain.model`.** El puerto de repositorio debe transportar la version de la fila (lectura -> escritura) para el optimistic locking, y los puertos de salida estan en el dominio. No se agrego `version` a `Paciente` para no tocar el agregado ni sus tests; con `@Version` en la entidad sigue haciendo falta transportarla, asi que no se elimino.
46. **`EventoTriaje` vive en `domain.event` (y no en `application.event`).** Es el argumento de `OutboxRepositoryPort`, que esta en el dominio, y el dominio no puede depender de `application`. `FabricaEventos` (que construye el evento desde un `CambioClinico`) si queda en `application.event`. Desviacion deliberada respecto del arbol propuesto.
47. **`ConflictoConcurrenciaException` vive en `domain.exception`** por la misma razon: la lanza el adapter al cumplir el contrato del puerto. `PacienteNoEncontradoException` (la lanzan los casos de uso) queda en `application.exception`.
48. **Los puertos de salida usan `Mono` dentro de `domain.port.out`:** es la unica dependencia de Reactor permitida en el dominio (regla de ArchUnit), porque el servicio es 100 % reactivo y el dominio define lo que necesita del exterior. Ver `docs/adr/0001-estructura-por-paquetes-con-archunit.md`.
49. **Los casos de uso se llaman `<Nombre>UseCaseImpl`** (`application.usecase`) y son clases planas sin anotaciones de Spring; `RegistroCambioClinico` (auditoria + outbox de un cambio) es un helper package-private de ese paquete. `RelojUtil` se elimino al truncar el `Clock` (ver 25).
50. **Un `Mapper` por entidad** (`PacienteMapper`, `AuditoriaMapper`, `OutboxEventoMapper`); el del outbox es un bean porque serializa con el `ObjectMapper` de Boot.
51. **`package-info.java` solo donde documenta algo** (`domain`, `domain.port.out`, `application`, `...postgres.entity`).

### Registro: validacion en el dominio y quejas configurables
52. **Toda la validacion del registro vive en `RegistradorPacientes`.** Recibe `DatosRegistro` (datos crudos), valida datos personales y signos vitales juntos (una sola `DatoClinicoInvalidoException` con todos los campos), construye `SignosVitales`, clasifica y asigna el SLA. El caso de uso solo traduce el comando a `DatosRegistro`, llama al dominio y orquesta persistencia, auditoria, outbox y metricas. `Paciente.registrar` conserva su propia comprobacion de invariantes (es la API publica del agregado); en el flujo normal ya llega validado y no produce un segundo error.
53. **`RegistradorPacientes` es una clase concreta** (sin interfaz): es logica pura con una sola implementacion y `application` puede depender de `domain`.
54. **Quejas administrativas configurables:** `triage.clasificacion.quejas-administrativas` (application.yml trae la lista del enunciado). La lista configurada **reemplaza** a la del enunciado, no se suma; una frase en blanco o una lista vacia impiden arrancar. Los constructores sin argumentos de `ClasificadorTriaje` y `QuejasAdministrativas` (lista del enunciado) se conservan para el dominio y sus tests.

### Escalamiento por SLA (RF-03, RN-03)
55. **El SLA es el guardado en el paciente** (`sla_minutos`, congelado al registrar). Cambiar `triage.sla.minutos.*` solo afecta a pacientes **nuevos**; los ya registrados conservan el SLA con el que entraron.
56. **Proceso de fondo con `Flux.interval`**, no `@Scheduled`: el caso de uso ya es reactivo y `concatMap` garantiza un solo ciclo a la vez por instancia (los ticks de un ciclo lento se descartan, no se acumulan). Un ciclo que falla se loguea y el siguiente corre igual. Cada ciclo lleva su propio `correlationId` (`escalamiento-sla-xxxxxxxx`, `outbox-xxxxxxxx`).
57. **Candidatos:** `estado = 'REGISTRADO' AND escalado = false AND fecha_registro + sla_minutos * 1 min < ahora` (estricto, supuesto 9), del mas antiguo al mas nuevo, hasta `triage.sla.lote` por ciclo (si hay mas, el resto va en el siguiente).
58. **Cada paciente va en su propia transaccion** y un fallo con uno no impide procesar a los demas. La decision es del dominio (`Paciente.escalar(ahora)`); la garantia ante concurrencia y varias replicas es el `UPDATE ... WHERE id = ? AND escalado = false AND estado = 'REGISTRADO'`: solo quien afecta 1 fila audita y escribe el evento (el indice unico parcial de auditoria es la segunda red). Con 0 filas no hace nada.
59. **El UPDATE del escalamiento incrementa `version`.** Asi, un cambio de estado que leyo al paciente antes del escalamiento falla (409) en vez de pisar el flag `escalado` con el valor viejo.
60. **SLA minimo = 1 minuto** (la unidad del dominio es el minuto entero). Para probar sin esperar: el perfil `local` usa SLA de 1-5 minutos y evaluacion cada 5 s; los tests usan un reloj mutable que "avanza el tiempo".
61. **Configuracion:** `triage.sla.habilitado`, `triage.sla.intervalo-evaluacion` (antes `triage.escalamiento.intervalo`, variable `TRIAGE_SLA_INTERVALO_EVALUACION`) y `triage.sla.lote`. Metrica: `triage.pacientes.escalados{nivel}`.

### Publicador del outbox
62. **El publicador es un caso de uso** (`PublicarEventosPendientesUseCase`) con el outbox y Kafka detras de puertos; un solo ciclo = una transaccion. `KafkaTemplate.send` puede bloquear (metadata, buffer): se invoca en `boundedElastic` envuelto con `Mono.fromFuture` y con timeout propio (`triage.outbox.timeout-publicacion`); ademas `max.block.ms`, `request.timeout.ms` y `delivery.timeout.ms` son cortos para fallar rapido con Kafka caido.
63. **Una sola fila por paciente por ciclo** (orden estricto por paciente, ver `docs/eventos.md`): los eventos de un mismo paciente salen en ciclos sucesivos (1 s por defecto). Es el coste de no desordenar entre replicas.
64. **Migracion V2** agrega `ultimo_intento_at` y `ultimo_error` al outbox (la V1 es inmutable) para el backoff y el diagnostico.
65. **Eventos agotados** (`intentos >= max-intentos`) no se reintentan solos ni cuentan como pendientes; bloquean los posteriores del mismo paciente hasta que alguien resetee `intentos`. Se decide asi para no publicar fuera de orden.

### Alertas (RF-06) — ver `docs/adr/0002-alertas-de-escalamiento-sse-y-webhook.md`
66. **Canal SSE efimero:** solo recibe las alertas emitidas mientras el cliente esta conectado (no se reenvian al conectarse). `GET /triage/alertas/stream` exige `X-API-Key` como el resto de `/triage/**`; el `Content-Type` es `text/event-stream`, evento `alerta`, `id` = `eventId`, y un comentario `keep-alive` cada `triage.alertas.sse.heartbeat`.
67. **Webhook opcional** (`triage.alertas.webhook.url`, vacia = deshabilitado): Retry solo ante timeout/conexion/5xx/429, CircuitBreaker que ignora 4xx. Un fallo se cuenta (`triage.alertas.fallos{canal}`) y se loguea; nunca afecta al SSE ni al consumidor.
68. **La alerta no lleva datos personales.** `minutosEspera` = minutos enteros entre `fechaRegistro` y el instante del escalamiento.
69. **Idempotencia en memoria** (LRU acotada) en lugar de tabla; consecuencia: tras un reinicio o rebalanceo puede repetirse una alerta. Con varias replicas cada alerta la emite una sola (reparto de particiones del grupo) y un cliente SSE solo ve las de su replica (limitacion documentada en el ADR).
70. **Consumidor:** `auto-offset-reset: earliest` (no pierde alertas si estuvo caido; en el primer arranque de un grupo nuevo puede alertar eventos viejos), listener que devuelve `Mono` (sin bloquear) y `DefaultErrorHandler` con backoff exponencial y DLT `triaje.eventos.DLT`; JSON invalido o evento incompleto = no recuperable, directo a la DLT.

### Tests
71. **Reloj mutable** (`RelojMutable`) para vencer SLA sin esperar y **broker Kafka propio** (`KafkaDePrueba`, pausa/reanuda el contenedor) para el caso de Kafka caido. Las clases que encienden procesos de fondo o el consumidor usan `@DirtiesContext(AFTER_CLASS)` y un `group-id` unico, para que no sigan vivos en el cache de contextos.
72. **`@DynamicPropertySource` de la clase base prevalece sobre el del hijo**; por eso `AbstractPostgresTest` (solo base de datos) esta separada de `AbstractIntegrationTest` (anade el Kafka compartido) y una clase con broker propio extiende la primera.

### Reporte de operación (RF-05) — ver `docs/reporte-operacion.md`
73. **La espera se asigna a la hora en que el paciente se registró**, no a la de su consulta. Quien pasó directo a `ALTA` o aún espera cuenta en `totalRegistrados` y `porNivel`, pero no en `pacientesAtendidos`, el promedio ni el p95 (esos agregados ignoran `NULL`).
74. **La primera entrada a consulta se toma de la auditoría** (`ROW_NUMBER() ... = 1` sobre `auditoria_clinica`), no de `pacientes.fecha_primera_consulta`: la auditoría es append-only y la fuente de verdad. Un test comprueba que ambas coinciden.
75. **Rango semiabierto `[desde, hasta)`**, fechas ISO 8601 **con offset o zona** (sin offset es 400). Horas de pared en `triage.reportes.zona-horaria` (por defecto `America/Bogota`); si `desde`/`hasta` no caen en punto, la primera/última hora es parcial. Una fila por hora, incluidas las vacías (ceros y `null`).
76. **Rango máximo configurable** (`triage.reportes.rango-maximo-dias`, 31 por defecto) y **minutos con 2 decimales redondeados en SQL**. Una sola consulta, en `db/queries/reporte-operacion.sql`, cargada una vez al arrancar; Java no agrega nada.
77. **Migración V3:** índice parcial `idx_auditoria_primera_consulta` (solo filas `EN_CONSULTA`) para un *index-only scan* en el cálculo de la primera consulta. La mejora medida es modesta; se deja porque independiza el plan del tamaño de la tabla y casi no cuesta en escritura.

### Documentación OpenAPI
78. **springdoc-openapi 2.8.x** (`springdoc-openapi-starter-webflux-ui`, compatible con Boot 3.5). La documentación va en los propios controllers (`@Tag`, `@Operation`, `@Parameter`, `@ApiResponses`), sin interfaces intermedias; los JSON de ejemplo viven en `controller.doc.EjemplosApi` y los DTOs llevan `@Schema`. Las anotaciones OpenAPI solo existen en `entrypoint.web` (y `OpenApiConfig` en `config`); `ArquitecturaTests` lo verifica.
79. **`/v3/api-docs`, `/swagger-ui.html`, `/swagger-ui/**` y `/webjars/**` son públicos** (sin API key) para poder abrir la interfaz y usar el botón *Authorize*; los endpoints de negocio siguen exigiendo `X-API-Key`. Se encienden con `SWAGGER_HABILITADO` (por defecto `true` en los perfiles `local` y `docker`, `false` en el resto): en producción conviene apagarlos, porque exponen el contrato de la API.
80. **El stream SSE no se puede probar bien desde Swagger UI** (no renderiza streams infinitos); se documenta con `text/event-stream` y un ejemplo, y se prueba con `curl -N`.
81. **springdoc genera el documento de forma perezosa** (escaneo del classpath en la primera petición a `/v3/api-docs`, luego cacheado). Es un bloqueo único y solo de documentación; BlockHound lo tiene permitido de forma explícita en los tests (`BlockHoundSupport`), igual que la resolución de recursos estáticos de Swagger UI.
