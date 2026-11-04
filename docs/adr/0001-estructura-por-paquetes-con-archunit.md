# ADR 0001 — Estructura por paquetes verificada con ArchUnit (en lugar de multi-módulo Gradle)

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** `triage-service` y `clinical-assistant-service`

## Contexto

Cada servicio sigue una arquitectura hexagonal (clean architecture): `domain` (reglas), `application` (casos de uso) e
`infrastructure` (adapters de entrada y de salida). La regla que importa es la **dirección de las dependencias**:
infrastructure → application → domain, y nunca al revés. Hay que decidir cómo se hace cumplir:

1. **Módulos Gradle** por capa (`domain`, `application`, `infrastructure`, `bootstrap`) dentro de cada servicio.
2. **Un solo módulo con paquetes** y las reglas verificadas por tests de **ArchUnit**.

Condiciones del proyecto: son dos servicios independientes (cada uno con su wrapper, su build y su despliegue; la
raíz es solo un composite build para el IDE), el equipo es pequeño y el repositorio se evalúa y se ejecuta con
`./gradlew build` dentro de cada servicio.

## Decisión

**Un módulo Gradle por servicio, organizado en paquetes, con ArchUnit como guardián de la arquitectura.**

La estructura (ver `CLAUDE.md`, «Estructura de paquetes») separa por rol: `domain.{model,model.enums,event,exception,service,port.out}`,
`application.{port.in,usecase,command,result,event,context,exception}` e
`infrastructure.{entrypoint.web.*,drivenadapter.*,config.*}`. Cada paquete contiene un solo tipo de cosa.

`ArquitecturaTests` falla el build si:

- `domain` depende de `application`, `infrastructure` o de un framework. **Excepción justificada:** Reactor solo en
  `domain.port.out`, porque los puertos de salida son reactivos (`Mono`) y el dominio es quien los define.
- `application` depende de `infrastructure` o de Spring, R2DBC, Jackson o Kafka.
- un controller usa un adapter, una entity o un puerto de salida (solo `application.port.in`).
- `entrypoint` y `drivenadapter` se conocen entre sí.
- aparece `@Table` fuera de `drivenadapter.postgres.entity`, o una entity se usa fuera de `drivenadapter.postgres`, o se usa JPA.
- los puertos (`domain.port.out`, `application.port.in`) contienen algo que no sea una interfaz.
- se rompen las convenciones de nombres (`*UseCaseImpl`, `*Controller`, `*Request`, `*Response`, `*Entity`, `*Mapper`, `*Adapter`).

## Alternativas consideradas

**Multi-módulo Gradle por capa.** El compilador impediría las dependencias prohibidas (un `domain` que ni ve Spring en su
classpath). Se descarta porque:

- Multiplica el build: 4 módulos × 2 servicios, cada uno con su `build.gradle`, y la raíz dejaría de ser un composite
  simple (el enunciado y `CLAUDE.md` piden servicios independientes y sin `settings.gradle` multi-módulo).
- Los puertos de salida viven en el dominio y devuelven `Mono`: el módulo `domain` igualmente necesitaría Reactor, así que
  el aislamiento «por classpath» ya no es total y hay que complementarlo con reglas de todos modos.
- Más fricción para refactorizar (mover una clase entre capas implica mover archivos entre módulos) y un cableado extra
  (módulo `bootstrap`) para algo que Spring Boot ya resuelve con el escaneo de paquetes.
- El Dockerfile y el pipeline de cada servicio se complican (más módulos que copiar y cachear).

**Solo convención, sin verificación.** Descartada: la estructura se erosiona en el primer apuro; sin un test que falle no es una regla.

## Consecuencias

- (+) Un único módulo por servicio: build, IDE y Docker simples; refactors baratos.
- (+) Las reglas son código ejecutable, versionado y revisable; un cambio que rompe una frontera falla en CI con un mensaje claro.
- (+) Cubren cosas que los módulos no pueden expresar (nombres, `@Table` solo en `entity`, controllers solo contra puertos de entrada).
- (−) El compilador no impide la violación: depende de que `ArquitecturaTests` se ejecute (corre con `./gradlew test`, sin Docker).
- (−) Hay que mantener las reglas al crecer; se prefiere ajustar el código antes que relajar una regla, y toda excepción
  (como Reactor en `domain.port.out`) se documenta aquí o en el `package-info` del paquete.
- Si en el futuro un equipo distinto necesita compartir el dominio entre servicios, este ADR se revisa y se extrae un módulo.
