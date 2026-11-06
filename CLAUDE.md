# CLAUDE.md — VidaCruz Triage Digital

Guía para trabajar en este monorepo. **Ante cualquier duda de negocio, consulta `docs/enunciado.md`** (reglas RN-01..RN-05, RF-01..RF-06, validaciones, pacientes E1-E6, corpus PROTO-001..003). Las decisiones de arquitectura viven en `docs/adr/`.

## Problema

VidaCruz opera 12 salas de urgencias con triaje manual: clasificación inconsistente, sin trazabilidad y sin protocolos a mano. Se construyen dos microservicios reactivos:

1. **triage-service** — registra pacientes con signos vitales, los clasifica por reglas objetivas (RN-01), vigila el SLA por nivel (RN-02), escala automáticamente (RF-03, idempotente RN-03), audita todo (RN-04), reporta operación con SQL avanzado (RF-05) y publica eventos a Kafka.
2. **clinical-assistant-service** — responde preguntas sobre protocolos clínicos con RAG (sin alucinar, con cita obligatoria: RN-05) y mantiene en Redis, a partir de los eventos Kafka, el estado de urgencias en tiempo real.

El triage **nunca** depende de la disponibilidad del asistente.

## Estructura

```
vidacruz-triage-digital/
├── docs/                      enunciado.pdf, enunciado.md, adr/, diagramas/
├── k8s/                       manifests (Azure: AKS / Container Apps)
├── triage-service/            Gradle independiente · com.vidacruz.triage
├── clinical-assistant-service/ Gradle independiente · com.vidacruz.assistant
├── docker-compose.yml         toda la plataforma con un comando
└── .env.example               variables (copiar a .env, que está en .gitignore)
```

Un solo repo git y un solo `.gitignore` en la raíz. La raíz tiene un `settings.gradle` **solo como composite build** (`includeBuild` de ambos servicios, para que IntelliJ los detecte) y un `build.gradle` mínimo con agregadores; no es multi-módulo: cada servicio tiene su propio wrapper y se construye/despliega por separado (los Dockerfiles usan la carpeta del servicio como contexto). DSL Gradle: **Groovy** (`build.gradle`) en ambos.

## Estructura de paquetes (triage-service y clinical-assistant-service)

Un paquete = un solo tipo de cosa. `triage-service` ya la implementa; `clinical-assistant-service` debe seguirla al crecer.

```
com.vidacruz.<servicio>
├── domain
│   ├── model            agregados y value objects (records, inmutables)
│   │   └── enums        NivelTriaje, EstadoPaciente, TipoEventoClinico
│   ├── event            eventos de dominio que viajan por los puertos (EventoTriaje)
│   ├── exception        errores de negocio (sin Spring)
│   ├── service          servicios de dominio (reglas): Clasificador, PoliticaSla, RegistradorPacientes
│   └── port.out         SOLO interfaces de lo que el dominio necesita (repositorios, outbox, transacción, métricas)
├── application
│   ├── port.in          SOLO interfaces de casos de uso
│   ├── usecase          implementaciones: <Nombre>UseCaseImpl (+ helpers package-private)
│   ├── command          records de entrada a los casos de uso
│   ├── result           records de salida de los casos de uso
│   ├── event            fábricas que construyen eventos desde el dominio (FabricaEventos)
│   ├── context          contexto transversal (ContextoCorrelacion: clave del Reactor Context)
│   └── exception        errores de aplicación (PacienteNoEncontradoException)
└── infrastructure
    ├── entrypoint.kafka   consumidor del topic (triage: alertas de escalamiento; asistente: estado de urgencias) · listener + exception/dto
    ├── entrypoint.scheduler  procesos de fondo reactivos (CicloPeriodico, Flux.interval) que llaman casos de uso
    ├── entrypoint.web   (adapters de entrada)
    │   ├── controller   <X>Controller; solo conocen application.port.in, command/result y dto
    │   │   └── doc      EjemplosApi: constantes con los JSON de ejemplo que usa la documentación OpenAPI
    │   ├── dto.request  <X>Request   (records + Bean Validation de presencia/tipo)
    │   ├── dto.response <X>Response  (records)
    │   ├── exception    @RestControllerAdvice + fábrica de ProblemDetail
    │   └── filter       WebFilters (CorrelationIdWebFilter)
    ├── drivenadapter    (adapters de salida)
    │   ├── postgres
    │   │   ├── adapter  <X>R2dbcAdapter / <X>Adapter: implementan domain.port.out (package-private)
    │   │   ├── repository <X>Repository: R2dbcRepository con @Query/@Modifying (SQL estático)
    │   │   ├── projection <X>Projection: records de resultados que no son una entidad
    │   │   ├── entity   <X>Entity: @Table de Spring Data R2DBC (NUNCA JPA)
    │   │   └── mapper   <X>Mapper: dominio <-> entity, explícito, uno por entidad
    │   ├── kafka        publicador del outbox hacia Kafka (triage) / productor-consumidor del asistente
    │   ├── memory       deduplicación de eventos en memoria (LRU)
    │   ├── sse          canal SSE de alertas (sink multicast)
    │   ├── webhook      canal webhook de alertas (WebClient + Resilience4j)
    │   ├── redis        (asistente) vector store y estado en tiempo real
    │   ├── ai           (asistente) cliente LLM + Resilience4j
    │   └── observability  métricas Micrometer y propagación Reactor -> MDC
    └── config           @Configuration, @ConfigurationProperties y calentamientos de arranque
        └── security     SecurityConfig, autenticación por API key, propiedades de seguridad
```

Reglas (las verifica `ArquitecturaTests`; si falla una, se corrige el código, no la regla):

- `domain` no depende de `application` ni `infrastructure`, ni de Spring, R2DBC, Kafka, Jackson, Lombok o Micrometer. **Reactor solo en `domain.port.out`** (los puertos devuelven `Mono`); el resto del dominio es Java puro.
- Los puertos de salida viven en `domain.port.out` (el dominio define lo que necesita) y se implementan en `infrastructure.drivenadapter`. Los de entrada viven en `application.port.in` y los implementa `application.usecase`. Ambos paquetes contienen **solo interfaces**.
- `application` no depende de `infrastructure` ni de Spring/R2DBC/Jackson/Kafka: los casos de uso son clases planas, cableadas en `infrastructure.config.ApplicationConfig`. La transacción se abstrae con `TransaccionPort`.
- Los controllers solo usan puertos de entrada: nada de adapters, entities ni puertos de salida. `entrypoint` y `drivenadapter` no se conocen entre sí.
- `@Table` solo en `infrastructure.drivenadapter.postgres.entity`, y las entities no salen de `drivenadapter.postgres`.
- Nombres: `*UseCaseImpl`, `*Controller`, `*Request`, `*Response`, `*Entity`, `*Mapper`, `*Adapter`. Los records que viajan por un caso de uso van en `command`/`result`; si son conceptos de negocio, en `domain.model`.
- Persistencia: `R2dbcEntityTemplate` para lo estándar (insert explícito porque el id lo asigna la aplicación, select por id, update con `@Version`).
  - **SQL estático → `@Query`** en interfaces `R2dbcRepository` dentro de `drivenadapter.postgres.repository` (`<X>Repository`). Los UPDATE/DELETE llevan `@Modifying` y devuelven `Mono<Integer>` con las filas afectadas; el adapter decide según ese valor (p. ej. `marcarEscalado`: 1 fila = escaló, 0 = ya estaba escalado, RN-03). `FOR UPDATE SKIP LOCKED` va también en un `@Query` y funciona dentro de la transacción reactiva del caso de uso.
  - Resultados que no son una entidad completa → records de proyección en `drivenadapter.postgres.projection` (`<X>Projection`).
  - Repositorios y proyecciones **no salen de `drivenadapter.postgres`**: solo los usan los adapters (ArchUnit lo verifica). Spring Data introspecciona la primera invocación de forma perezosa (lee el `.class` del disco), así que `CalentamientoRepositorios` ejecuta cada consulta una vez al arrancar para no bloquear el event loop.
  - **`DatabaseClient` solo para SQL dinámico** (se arma en tiempo de ejecución: filtros opcionales, ORDER BY variable…), siempre con un comentario que explique por qué no cabe en un `@Query`. Hoy el único `DatabaseClient` es el del reporte RF-05, justificado por la segunda regla.
  - **Consultas muy largas** (p. ej. el reporte RF-05 con CTE/window functions) → archivo `.sql` en `src/main/resources/db/queries/` cargado por el adapter **una sola vez al arrancar** (no en cada petición) y ejecutado con `DatabaseClient` (`ReporteOperacionR2dbcAdapter`), no una cadena gigante en Java. Cada bloque (CTE) lleva un comentario breve; los parámetros son siempre con nombre.
- El asistente aplica lo mismo; sus adapters específicos (`kafka`, `redis`, `ai`) cuelgan de `drivenadapter` y, si consume Kafka, el consumidor es un adapter de **entrada** (`infrastructure.entrypoint.kafka`).

## Decisiones de arquitectura (respetarlas)

- **Hexagonal por servicio**, con la estructura de la sección «Estructura de paquetes». Dependencias solo hacia adentro: infrastructure → application → domain. Se hace cumplir con ArchUnit (`ArquitecturaTests`), no con módulos Gradle (ver `docs/adr/0001-estructura-por-paquetes-con-archunit.md`).
- **100 % reactivo:** WebFlux + `Mono`/`Flux`, R2DBC para PostgreSQL. **Cero llamadas bloqueantes en el event loop.** Lo inevitablemente bloqueante (p. ej. cliente LLM de Spring AI, Jedis del vector store) se envuelve con `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`. Flyway es JDBC pero solo corre al arrancar.
- **Kafka con `spring-kafka`** (NO reactor-kafka, en mantenimiento): `KafkaTemplate` envuelto con `Mono.fromFuture`. Los **listeners son síncronos por registro** (`block(timeout)` en el hilo del consumidor, `ack-mode: record`); **no** devolver `Mono` desde un `@KafkaListener`: en spring-kafka 3.3 un fallo dentro del `Mono` se pierde (ADR 0004). Topic `triaje.eventos`; mensajes JSON con envelope `{eventId (UUID), eventType, occurredAt, version, payload}`. Tipos: `paciente_registrado`, `paciente_en_consulta`, `paciente_escalado`, `paciente_alta`. Clave del mensaje = id del paciente (orden por paciente).
- **Transactional Outbox** en triage: cambio de estado + auditoría + evento en la **misma transacción R2DBC**; un publicador reactivo envía `outbox_eventos` a Kafka (`FOR UPDATE SKIP LOCKED`, seguro con varias réplicas; sin ShedLock porque no existe proveedor R2DBC y exigiría JDBC bloqueante).
- **Consumidor idempotente** en el asistente: `eventId` deduplicado en Redis (`SET ... EX`) **dentro del mismo script Lua** que aplica el evento, para que sea atómico. Ver sección «Asistente: estado de urgencias».
- **DLQ:** `triaje.eventos.DLT` con `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, backoff exponencial; excepciones no recuperables (JSON inválido, evento mal formado) directo a DLT.
- **Resilience4j sobre el LLM** (instancia `llm`): CircuitBreaker + Retry (**solo** transitorios: timeout, 5xx, 429) + TimeLimiter + Bulkhead. Fallback = HTTP **503** con mensaje claro.
- **Sin Saga** (se justificará en un ADR): no hay transacción distribuida; el asistente solo proyecta eventos.
- **Escalamiento idempotente** con `UPDATE` condicional atómico (`WHERE escalado = false AND estado = 'REGISTRADO'`); la fila afectada decide si se audita y se emite evento. Red de seguridad en BD: índice único parcial en auditoría.
- **Redis Stack:** vector store (Spring AI) + vista materializada de estado-urgencias.
- **Seguridad:** API key por header `X-API-Key` con `WebFilter` de Spring Security, valores por variables de entorno. **Ninguna credencial hardcodeada**. Solo `/actuator/health/**` es público.
- **Observabilidad:** Actuator (liveness/readiness), Micrometer + Prometheus, logs JSON estructurados (nativo de Boot: `logging.structured.format.console=logstash`) con `correlationId`/`traceId`, OpenTelemetry (OTLP, desactivado por defecto). Propagación de contexto Reactor → MDC con `context-propagation`.
- **Stack:** Java 21 (toolchain), Spring Boot **3.5.x**, Spring AI **1.1.x** (la 2.x exige Boot 4), Resilience4j 2.4 (`resilience4j-spring-boot3` + `resilience4j-reactor`), Flyway, Testcontainers **2.0.5**.

## Convenciones de código

### Documentación OpenAPI (triage-service; aplicar igual en clinical-assistant-service)

- Dependencia `springdoc-openapi-starter-webflux-ui` (2.8.x con Boot 3.5). Swagger UI en `/swagger-ui.html`, contrato en `/v3/api-docs`; ambos **públicos** (sin API key) y controlados por `SWAGGER_HABILITADO` (`true` en los perfiles `local` y `docker`, `false` por defecto: en producción se apagan).
- **La documentación va en el propio controller, sin interfaces `*Api`** (convención real, la misma en triage y en el asistente): `@Tag` en la clase y, en cada método, `@Operation` (summary + descripción que cita la regla de negocio: RN-01, RF-03…), `@Parameter` en los parámetros y `@ApiResponses` con todos los códigos posibles (200/201, 400, 401, 404, 409, 500) usando `ProblemDetail` (`application/problem+json`) y ejemplos reales. Los JSON de ejemplo se comparten como constantes en `entrypoint.web.controller.doc.EjemplosApi` (única clase de ese paquete) para no inflar el controller.
- Los DTOs de request/response llevan `@Schema` (descripción, ejemplo, `minimum`/`maximum` según el enunciado, valores del enum).
- `OpenApiConfig` (en `infrastructure.config`) define título, versión, enlace al README y el `SecurityScheme` apiKey `X-API-Key` aplicado globalmente (botón *Authorize*).
- **Nunca** anotaciones OpenAPI en `domain`, `application` ni `drivenadapter` (regla de `ArquitecturaTests`). Un endpoint nuevo no está terminado sin sus anotaciones, sus `@Schema` y su prueba en `OpenApiTests`.

- Paquetes base: `com.vidacruz.triage` y `com.vidacruz.assistant`, con la misma estructura (sección «Estructura de paquetes»). `package-info.java` solo donde documente algo útil (reglas de la capa, por qué un paquete es especial); no se crean vacíos.
- **Español para el dominio clínico**, tal como en el enunciado: `Paciente`, `NivelTriaje`, `EstadoPaciente`, `SignosVitales`, `fechaRegistro`, `slaMinutos`, `quejaPrincipal`; endpoints y JSON en español (`/triage/pacientes`, `estado`, `escalado`); tablas/columnas SQL en español `snake_case`. **Inglés para lo técnico**: `Controller`, `Repository`, `UseCase`, `Adapter`, `Properties`, `OutboxPublisher`, `SecurityConfig`.
- Eventos Kafka: `eventType` en minúsculas con guion bajo (`paciente_escalado`).
- Lombok solo para boilerplate (getters, builders, logs); el dominio prefiere `record`s e inmutabilidad.
- Constructor injection; sin `@Autowired` en campos. Propiedades tipadas con `@ConfigurationProperties`; nada de `@Value` disperso.
- Errores: excepciones de dominio → `@RestControllerAdvice` global con cuerpo estable (400 validación con campo inválido, 404, 409 transición inválida, 503 modo degradado).
- Tiempos con `Instant`/`TIMESTAMPTZ` (UTC) y reloj inyectable (`Clock`) para testear.

### Literales (ambos servicios)

Sin una clase `Constantes` global: cada literal vive junto a lo que lo usa, y lo compartido se agrupa por tema.

- **Framework:** `MediaType.*_VALUE` (también en `@Content(mediaType=…)`), `HttpHeaders.*`; nunca `"application/problem+json"` escrito a mano.
- **Valores de dominio** (`CRITICO`, `EN_CONSULTA`, `paciente_escalado`…): el enum (`name()`, `nombreEvento()`/`codigo()`). Excepción: las anotaciones de OpenAPI exigen constantes de compilación, así que `ValoresApi` (triage) las repite y `ValoresApiTests` las compara con los enums.
- **Claves técnicas compartidas:** clases pequeñas `final`, sin lógica, en la capa que las usa: `CabecerasHttp` (`X-Correlation-Id`), `CabecerasKafka`, `ClavesMdc`, `NombresMetricas` (nombres y tags de métricas), `PropiedadesProblema` (campos extra del error).
- **Errores de la API:** enum `ErrorApi` (status + `type` URI estable) en `entrypoint/web/exception`; los textos de usuario en `messages.properties` (`MessageSource`, listo para otros idiomas). `GlobalExceptionHandler` y `Problemas` solo usan el catálogo. Un error nuevo = entrada en `ErrorApi` + claves en `messages.properties` + fila en `docs/errores.md`.
- **Ejemplos de OpenAPI:** valores repetidos (fechas, UUID…) como constantes con nombre en `EjemplosApi`.
- **Valores de negocio con una sola fuente de verdad en el dominio** (rangos, SLA, umbrales): cada uno se define una vez y todo lo demas (validacion, configuracion, OpenAPI, docs) lo lee de ahi, sin repetir el numero. Rangos de entrada: `RangoClinico` (enum) con sus limites como constantes de compilacion en `RangosClinicos` (las anotaciones `@Schema` los usan; `RangosOpenApiTests` lo verifica). SLA por defecto: `NivelTriaje.slaPorDefectoMinutos()`; `triage.sla.minutos.*` en `application.yml` solo sobrescribe por variable de entorno (vacia = el valor del enum). **Excepcion deliberada:** los umbrales de `ClasificadorTriaje` quedan escritos en la tabla de reglas porque replican la RN-01 fila por fila y se leen mejor asi.
- Descripciones de OpenAPI repetidas (401, 404, 500, id del paciente): constantes `DESC_*` en `EjemplosApi`.
- **Se dejan en su sitio:** mensajes de log, mensajes de invariantes de dominio (`IllegalArgumentException`), SQL, nombres de columnas/claves JSON de un solo uso y literales de tests.
- `LiteralesProhibidosTests` (en cada servicio) falla si el código de producción escribe `"application/problem+json"` o el nombre de un valor de dominio como literal completo (con excepciones justificadas en el propio test).

## Reglas de trabajo

1. **Nunca bloquear el event loop ni los schedulers de Reactor** (`parallel`, `single`): nada de `.block()`, `Thread.sleep`, JDBC ni clientes síncronos fuera de `boundedElastic`. Usa BlockHound en tests si hace falta. **Única excepción:** en el hilo de un consumidor Kafka (`@KafkaListener`, hilo dedicado del contenedor) se permite `block(timeout)` para garantizar el orden por partición y que el offset se confirme solo al terminar; solo en el listener, nunca en casos de uso, adapters ni controllers. Ver `docs/adr/0004-consumo-sincrono-de-kafka-en-servicios-reactivos.md` y el supuesto 89 de `docs/supuestos.md`.
2. **Tests obligatorios por funcionalidad** (unitarios + integración; Testcontainers para PostgreSQL/Kafka/Redis; WireMock para el LLM). Mínimo del enunciado: clasificación E1-E6, transiciones válidas/inválidas, idempotencia del escalamiento, citas del RAG y caso «sin coincidencia, sin alucinación».
3. **Commits pequeños con Conventional Commits** (`feat(triage): ...`, `fix(assistant): ...`, `chore:`, `docs:`, `test:`, `build:`, `ci:`). Un cambio lógico por commit, con su test.
4. Consulta `docs/enunciado.md` ante cualquier duda de negocio; si hay ambigüedad, documenta el supuesto (ADR o README).
5. Secretos solo por entorno; `.env` jamás se versiona (usa `.env.example`).
6. Migraciones Flyway inmutables una vez aplicadas: los cambios van en un `V{n}__...sql` nuevo.
7. La auditoría es append-only: sin endpoints de borrado/edición y con trigger en BD.

## Comandos

Ejecutar dentro de cada servicio (`cd triage-service` / `cd clinical-assistant-service`); en Windows usar `gradlew.bat` o Git Bash.

```bash
./gradlew build -x test          # compilar + empaquetar sin tests
./gradlew test                   # tests + reporte de cobertura (Testcontainers requiere Docker en marcha)
./gradlew check                  # test + cobertura + verificación del umbral (lo que ejecuta el CI)
./gradlew build                  # compilar + tests
./gradlew bootRun                # levantar con perfil "local" (necesita la infra del compose y variables del .env)
./gradlew bootJar -x test        # jar ejecutable (build/libs/)
```

Desde la raíz (composite build; wrapper propio, misma versión de Gradle):

```bash
./gradlew assembleAll            # compilar + empaquetar ambos servicios SIN tests (-x test no cruza a builds incluidos)
./gradlew buildAll               # build + tests de ambos
./gradlew testAll                # solo tests de ambos
./gradlew :triage-service:build  # un servicio concreto
./gradlew :clinical-assistant-service:bootRun
```

Toda la plataforma (desde la raíz):

```bash
cp .env.example .env             # primera vez; editar secretos (el perfil local de AMBOS servicios lo lee solo: spring.config.import)
docker compose up --build        # postgres, kafka (KRaft) + topics, redis-stack y ambos servicios
docker compose up -d postgres kafka kafka-init redis   # solo infraestructura, para bootRun en el host
docker compose down -v           # apagar y borrar volúmenes
```

Swagger UI: `http://localhost:8081/swagger-ui.html` (triage) y `http://localhost:8082/swagger-ui.html` (asistente) · contrato OpenAPI: `/v3/api-docs`. Pulsar *Authorize* y pegar `TRIAGE_API_KEY`.

Puertos del host: triage `8081`, asistente `8082`, PostgreSQL `5432`, Kafka (listener externo) `29092`, Redis `6379`, RedisInsight `8001`. Salud: `GET /actuator/health/readiness`.

Perfiles: `local` (por defecto, hosts en localhost) · `docker` (hosts = nombres de servicio) · `test` (Testcontainers). Variables clave: ver `.env.example` (SLA por nivel `TRIAGE_SLA_*`, intervalo de evaluación del SLA `TRIAGE_SLA_INTERVALO_EVALUACION`, webhook de alertas `TRIAGE_ALERTAS_WEBHOOK_URL`, `LLM_*`, API keys).

### Despliegue en Azure (AKS)

Decisiones en `docs/adr/0008-despliegue-en-aks.md`; guía, costos y verificación en `docs/despliegue-azure.md`. Requiere `az` (con `az login`), `kubectl`, Docker en marcha y `LLM_API_KEY` en el entorno o en el `.env` de la raíz.

```bash
./infra/deploy.sh cuota|plan|infra|imagenes|credenciales|secretos|aplicar|verificar   # por pasos, idempotente (deploy.ps1 -Paso <paso> en PowerShell)
./infra/deploy.sh                      # todo: cuota -> infra -> imagenes -> credenciales -> secretos -> aplicar -> verificar
kubectl kustomize k8s/overlays/azure   # ver los manifests renderizados (marcadores ACR_LOGIN_SERVER, IMAGE_TAG, DNS_LABEL_*)
kubectl get pods,svc -n vidacruz       # estado en el clúster
./infra/destroy.sh                     # BORRA el grupo de recursos (pide confirmación; destroy.ps1 en PowerShell)
```

* `infra/` = Bicep (grupo + ACR + AKS) y scripts; `k8s/base` + `k8s/overlays/azure` = Kustomize. Perfil de Spring `k8s` (`application-k8s.yml`, hosts = nombres de Service).
* **Nada de la cuenta se versiona:** el overlay lleva marcadores que `deploy.*` sustituye; los Secrets los crea `deploy.*` (`infra/.env.azure` y `.env` están en `.gitignore`); `k8s/secret.example.yaml` no tiene valores. Nunca imprimas claves ni el ID de suscripción.
* Imágenes: `docker build --platform linux/amd64` + push al ACR (ACR Tasks / `az acr build` está bloqueado en suscripciones de prueba). En todos los pods, `enableServiceLinks: false` (si no, `KAFKA_PORT`/`REDIS_PORT` inyectadas rompen la configuración).
* Nodo `Standard_D4s_v4`, 4 vCPU de cuota: una réplica de cada cosa y `maxSurge: 0`. Cuesta ≈ 6 USD/día encendido: destruir al terminar.

### Cobertura, CI y trazas distribuidas

* **Cobertura (JaCoCo, en ambos servicios):** `./gradlew test` deja el reporte en `build/reports/jacoco/test/` (`index.html` y `jacocoTestReport.xml`); `./gradlew check` = test + reporte + `jacocoTestCoverageVerification` (umbral de líneas y ramas en el `build.gradle`, unos puntos por debajo del valor real). Las exclusiones (`*Application`, `config/*Config`, `*Properties`, clases de constantes) están justificadas en `docs/cobertura.md`; `lombok.config` marca lo generado como `@Generated`. Al subir la cobertura, sube también el umbral; si baja de él, el build falla.
* **CI (`.github/workflows/ci.yml`):** push y PR a `main`, un job por servicio con `./gradlew check`. **Deploy (`deploy.yml`):** manual o tras CI verde (si `DEPLOY_AUTOMATICO=true`); construye con Docker en el runner, sube al ACR y hace `kubectl set image`; autenticación OIDC sin secretos (comandos en `docs/despliegue-azure.md`, no se ejecutan sin confirmación).
* **Trazas:** Micrometer Tracing + OpenTelemetry, exportación apagada por defecto (`OTEL_EXPORT_ENABLED`). Observación activa en `KafkaTemplate` y en los listener containers (`spring.kafka.*.observation-enabled`), r2dbc-proxy en triage (`ObservacionR2dbcConfig`), Lettuce en el asistente (`ObservacionRedisConfig`). El salto asíncrono del outbox se resuelve guardando el `traceparent` en `outbox_eventos` (migración `V4`) y restaurándolo al publicar (`ContextoTraza`). Jaeger: `OTEL_EXPORT_ENABLED=true docker compose --profile observabilidad up --build` → <http://localhost:16686>. Detalle y límites: `docs/observabilidad.md`.
* **Reglas aprendidas con las trazas:** (1) el propagador W3C se declara explícito (`PropagacionTrazasConfig`): sin él Boot dejaba uno vacío y no salía ningún `traceparent`; (2) cualquier cosa nueva de tracing se **calienta en el arranque** (`CalentamientoTrazas`, `@Order` máximo): la primera vez, OpenTelemetry lee jars con `ServiceLoader` y BlockHound lo detecta si ocurre en el event loop; (3) en un `Mono` el span vive en el **contexto de Reactor**, no necesariamente en el hilo: captúralo con `deferContextual`, no con `tracer.currentSpan()`.
* **Matriz de trazabilidad:** `docs/trazabilidad.md` enlaza cada requerimiento con su implementación y sus pruebas y lista las brechas. Si añades o cambias un requerimiento, actualízala.

### Demo, HTTPS y límite de consultas

* **Demo de punta a punta:** `scripts/demo.ps1` / `scripts/demo.sh` (local o Azure; URLs y claves por parámetros, entorno o `-ArchivoEnv`, nunca en el código) y `scripts/requests.http` con `http-client.env.json` (las claves van en `http-client.private.env.json`, ignorado). Cuerpos y preguntas **sin tildes**: el `curl` de Windows no siempre envía UTF-8. Si cambias un endpoint o una regla, actualiza la demo.
* **HTTPS en Azure ([ADR 0009](docs/adr/0009-https-con-ingress-nginx-y-cert-manager.md)):** `k8s/overlays/azure/ingress.yaml` (un Ingress con TLS por servicio) + `k8s/platform/` (ingress-nginx vendorizado con dos `Service` LoadBalancer, uno por host, y los `ClusterIssuer` de Let's Encrypt HTTP-01) + paso `plataforma` de `infra/deploy.*` (`-Emisor staging` para probar sin gastar cupo). Los Services de los microservicios son `ClusterIP`; el perfil `k8s` confía en `X-Forwarded-*` (`server.forward-headers-strategy: framework`). ingress-nginx está archivado upstream: no es para producción.
* **Límite de consultas (asistente):** `LimiteConsultasWebFilter` (Resilience4j `RateLimiter`, por API key, `assistant.rate-limit.*`, 20/min por defecto); 429 `demasiadas-peticiones` con `Retry-After`; métrica `asistente_consultas_limitadas_total`. En `application-test.yml` el cupo es enorme; `LimiteConsultasTests` fija uno bajo en su propio contexto (con `group-id` propio y `@DirtiesContext`, porque dos contextos vivos con el mismo grupo se reparten las particiones de Kafka). Los filtros de `entrypoint` no pueden importar `drivenadapter` (ArchUnit).
* **CI:** además de `check` por servicio, el job `manifiestos` valida `docker compose config` y los manifests con kubeconform, y `secretos` corre gitleaks sobre todo el historial.

### Testcontainers 2.x (decisión)

- Se fuerza `testcontainers.version = 2.0.5` en ambos `build.gradle` (`ext`), sobrescribiendo la 1.21.x que gestiona Spring Boot 3.5. Motivo: la 1.21.x (docker-java 3.4.x) falla con `Could not find a valid Docker environment` / `Status 500` contra Docker Engine reciente en Windows.
- Artefactos 2.x con prefijo: `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `testcontainers-r2dbc`, `testcontainers-kafka`. Clases movidas: `org.testcontainers.postgresql.PostgreSQLContainer` (ya no genérico); `KafkaContainer` en `org.testcontainers.kafka`.
- **No usar `@ServiceConnection`** ni `spring-boot-testcontainers`: Boot 3.5 solo reconoce los contenedores 1.x. Las propiedades se inyectan con `@DynamicPropertySource`.
- Los tests de integración extienden `AbstractIntegrationTest` y obtienen Postgres/Kafka de `Contenedores` (singletons por JVM, no un contenedor por clase).
- Los tests de integracion web (`PacienteApiTests`, `AtomicidadTransaccionalTests`) corren con **BlockHound** activo (`BlockHoundSupport`; la JVM de test lleva `-XX:+AllowRedefinitionToAddDeleteMethods`). Si BlockHound falla un test, hay una llamada bloqueante real en el event loop: arreglala o envuelvela en `boundedElastic`; solo se permiten excepciones de libreria en `BlockHoundSupport`.

### Reporte de operación (RF-05, triage-service)

- `GET /triage/reportes/operacion?desde&hasta` (ISO 8601 con offset, `[desde, hasta)`). Una sola consulta SQL en `db/queries/reporte-operacion.sql` (CTEs, `ROW_NUMBER`, `FILTER`, `percentile_cont`); Java solo mapea. Explicación, supuestos y EXPLAIN en `docs/reporte-operacion.md`.
- Config: `triage.reportes.zona-horaria` (`America/Bogota`) y `triage.reportes.rango-maximo-dias` (31).
- Tests (`ReporteOperacionTests`): dataset determinista insertado por SQL; cada test usa su propio día de 2020 porque la auditoría es append-only y no se puede limpiar.

### Asistente: estado de urgencias (clinical-assistant-service)

- **RF-04 parte B:** `EventosTriajeKafkaListener` (grupo `asistente-estado-urgencias`) → `AplicarEventoClinicoUseCase` → `EstadoUrgenciasPort` (Redis). `GET /asistente/estado-urgencias` lee la vista; **nunca** se llama a la API de triage (regla de ArchUnit). El envelope del evento se duplica en el asistente a propósito: ADR 0003.
- **Dominio sin Redis:** `VistaUrgencias` es la especificación ejecutable de las reglas (registrado/consulta/escalado/alta, duplicados, desconocidos); el adapter las aplica de forma atómica con `resources/redis/aplicar-evento.lua` (un script por evento: dedupe por `eventId` + paciente + contadores + último evento). Si cambias una regla, cámbiala en **ambos** sitios: `ScriptLuaTests` compara Redis con el modelo.
- **Listener síncrono por registro, no `Mono`** (igual que el consumidor de alertas de triage): ADR 0004. **No cambiar a `Mono` sin repetir los tests de Redis caído y de DLT.**
- **Errores:** `ErrorHandlingDeserializer` + JSON; ilegible o incompleto → `triaje.eventos.DLT` directo; Redis caído → backoff exponencial y offset sin confirmar; `eventType` desconocido → ignorado (no DLT). `auto.offset.reset=earliest`: el topic debe retener los eventos de los pacientes activos (sin `compact`).
- **Redis:** prefijo `asistente:urgencias:` (`ASSISTANT_REDIS_PREFIX`) para no chocar con el vector store del RAG. Spring AI está excluido del autoconfigurado hasta implementar el RAG (RF-04 parte A).
- **RAG (RF-04 parte A):** `ConsultarProtocoloUseCaseImpl` orquesta `BuscadorProtocolosPort` → `GeneradorRespuestaPort` → `ValidadorRespuesta` (tres barreras de RN-05; detalle en `docs/asistente-rag.md`). Corpus en `resources/protocolos/*.md` (soltar un archivo = nuevo protocolo); fragmentación por sección; vector store Redis Stack armado a mano en `RagConfig` (ADR 0005). La indexación corre en segundo plano con backoff (`IndexacionProtocolosArranque`) y expone el indicador de readiness `indiceProtocolos`. El umbral de similitud (0.75) está **medido con OpenAI real**; el hash de cada fragmento incluye el modelo de embeddings (cambiarlo reindexa). Al agregar protocolos o cambiar de modelo, repetir `ProveedorRealTests` (`LLM_API_KEY=… ./gradlew cleanTest test --tests '*ProveedorRealTests' -i`).
- **Llamadas al proveedor de IA:** siempre por `ProtectorLlm` (`subscribeOn(boundedElastic)` + Bulkhead/TimeLimiter/CircuitBreaker/Retry, instancias `llm-chat` y `llm-embedding`). Los reintentos propios de Spring AI están apagados; el Retry solo repite transitorios (`ErrorTransitorioProveedor`). Falla final → `AsistenteNoDisponibleException` → 503. **La validación de la respuesta va fuera de las llamadas protegidas** (no cuenta como falla del circuit breaker).
- **Tests del RAG:** el proveedor es un WireMock compartido (`ProveedorLlmSimulado`: embeddings deterministas por palabras clave + chat por reglas); `AbstractIntegrationTest` lo reinicia y cierra los circuit breakers antes de cada test. `ProveedorRealTests` (opcional, `LLM_API_KEY`) calibra el umbral. Para tests que arrancan con el proveedor caído, sobreescribir `esperarIndiceListo()`.
- **Local:** el perfil `local` del asistente importa el `.env` de la raíz (`spring.config.import`), así que `./gradlew bootRun` basta con `docker compose up -d postgres kafka kafka-init redis`. Swagger UI: `http://localhost:8082/swagger-ui.html`.
- **Tests:** `AbstractIntegrationTest` (Redis `redis/redis-stack-server` + Kafka compartidos, BlockHound, Redis vaciado antes de cada test), `KafkaDePrueba` (productor, lector de DLT, lag del grupo), `EventosTriaje` (eventos con el formato de `docs/eventos.md`). Los tests de Redis caído usan `docker pause` sobre el contenedor compartido y lo reanudan en `finally`.

### Procesos de fondo y alertas (triage-service)

- **Escalamiento por SLA** (RF-03): `EscalarPacientesVencidosUseCase`, disparado por `CicloPeriodico` cada `triage.sla.intervalo-evaluacion`. UPDATE condicional atómico por paciente en su propia transacción; solo quien afecta 1 fila audita y escribe el evento. El SLA es el guardado en el paciente (un cambio de configuración solo afecta a pacientes nuevos). SLA mínimo del dominio: 1 minuto; el perfil `local` usa 1-5 min y evaluación cada 5 s.
- **Publicador del outbox**: `PublicarEventosPendientesUseCase` (`FOR UPDATE SKIP LOCKED`, un evento por paciente por ciclo, backoff exponencial, métricas `triage.outbox.*`). Kafka caído no rompe el registro.
- **Alertas** (RF-06, ADR 0002; listener síncrono por registro, ADR 0004): consumidor del grupo `triage-alertas` → `NotificarEscalamientoUseCase` → SSE (`GET /triage/alertas/stream`, con `X-API-Key`) y webhook opcional. Sin nombre del paciente. Mensajes inválidos → `triaje.eventos.DLT`.
- Apagar/encender: `triage.sla.habilitado`, `triage.outbox.habilitado`, `triage.alertas.consumidor.habilitado` (en el perfil `test` los tres arrancan **apagados**).
- Tests de procesos: `RelojMutable` para vencer SLA, `KafkaDePrueba` (broker que se pausa), `AbstractPostgresTest` (solo BD) vs `AbstractIntegrationTest` (BD + Kafka compartido; el `@DynamicPropertySource` de la base prevalece sobre el del hijo). Las clases que encienden procesos usan `@DirtiesContext` y un `group-id` único.
