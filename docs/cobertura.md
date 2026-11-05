# Cobertura de pruebas (JaCoCo)

Reportes de cobertura de líneas y ramas de los dos servicios, el umbral que hace fallar el build y por qué se excluye lo que se
excluye. Se generan con `./gradlew test` (HTML en `build/reports/jacoco/test/index.html`, XML en `jacocoTestReport.xml`) y
`./gradlew check` añade la verificación del umbral (`jacocoTestCoverageVerification`); el CI sube ambos reportes como artefactos.

## Resultado

| Servicio | Tests | Líneas | Ramas | Umbral (líneas / ramas) |
|---|---:|---|---|---|
| `triage-service` | 303 | **96.8 % (953/984)** | **81.1 % (223/275)** | ≥ 92 % / ≥ 76 % |
| `clinical-assistant-service` | 175 (3 omitidos) | **95.5 % (939/983)** | **78.1 % (282/361)** | ≥ 91 % / ≥ 73 % |

El umbral está **unos puntos por debajo del valor actual** (margen para cambios pequeños sin que el build falle por ruido),
pero lo bastante cerca como para que una bajada real de cobertura rompa `check`. Al mejorar la cobertura, se sube el umbral en el
`build.gradle` de cada servicio (bloque `jacocoTestCoverageVerification`).

## Por capa

### `triage-service`

| Capa | Líneas | Ramas |
|---|---|---|
| domain | 98.2 % (221/225) | 92.3 % (108/117) |
| application | 95.5 % (192/201) | 80.8 % (21/26) |
| infrastructure · entrypoint (web, kafka, scheduler, health) | 96.1 % (219/228) | 73.1 % (57/78) |
| infrastructure · drivenadapter (postgres, kafka, redis, ai…) | 97.3 % (255/262) | 66.7 % (32/48) |
| infrastructure · config (seguridad y arranque) | 97.1 % (66/68) | 83.3 % (5/6) |

### `clinical-assistant-service`

| Capa | Líneas | Ramas |
|---|---|---|
| domain | 96.9 % (280/289) | 87.7 % (121/138) |
| application | 97.1 % (134/138) | 87.1 % (27/31) |
| infrastructure · entrypoint (web, kafka, scheduler, health) | 89.7 % (157/175) | 65.0 % (39/60) |
| infrastructure · drivenadapter (postgres, kafka, redis, ai…) | 96.5 % (306/317) | 71.4 % (90/126) |
| infrastructure · config (seguridad y arranque) | 96.9 % (62/64) | 83.3 % (5/6) |

Lectura: el **dominio y la aplicación** (reglas de negocio) están por encima del 95 % de líneas; las ramas son más bajas en
`drivenadapter` y `entrypoint` porque ahí están los caminos de error de infraestructura (timeouts, brokers caídos, reintentos)
que solo se ejercitan parcialmente. Las clases con menos cobertura y su motivo se discuten en la matriz de
[trazabilidad](trazabilidad.md#brechas) (p. ej. el circuit breaker del webhook).

## Qué se excluye y por qué

Solo se excluye lo que no tiene lógica propia que probar. Las exclusiones están en el `build.gradle` de cada servicio
(`exclusionesCobertura`), cada una con su motivo:

| Patrón | Motivo |
|---|---|
| `**/*Application.class` | `main()` de Spring Boot: arranque, sin lógica. |
| `**/infrastructure/config/*Config.class` | `@Configuration`: cableado de beans. Se ejercita al levantar el contexto en todos los tests de integración; no hay comportamiento propio que afirmar. |
| `**/infrastructure/config/*Properties*.class` | `@ConfigurationProperties`: contenedores de valores tipados, sin comportamiento. |
| `**/controller/doc/EjemplosApi*.class` | Constantes con los JSON de ejemplo de OpenAPI. |
| `**/dto/ValoresApi.class` | Constantes de compilación para las anotaciones de OpenAPI (`ValoresApiTests` las compara con los enums). |
| `**/CabecerasHttp`, `CabecerasKafka`, `ClavesMdc`, `NombresMetricas`, `PropiedadesProblema` | Clases de solo constantes (claves técnicas compartidas). |
| `**/package-info.class` | Solo documentación. |

Lo que **no** se excluye aunque pudiera parecerlo: los DTO `*Request`/`*Response` (tienen lógica de mapeo), las excepciones, los
adapters y la seguridad (`config/security`). JaCoCo ya ignora por sí mismo los métodos que el compilador genera en los `record`
(`equals`, `hashCode`, `toString`, accesores), así que los records sin lógica no necesitan exclusión; y `lombok.config`
(`lombok.addLombokGeneratedAnnotation = true`) marca como `@Generated` lo que genera Lombok (getters, setters, builders).

## Cómo verla

```bash
cd triage-service && ./gradlew test        # abre build/reports/jacoco/test/index.html
cd clinical-assistant-service && ./gradlew check   # test + reporte + verificación del umbral
```

En el CI (GitHub Actions) cada job publica un resumen con los porcentajes y sube `build/reports/jacoco/test` como artefacto.

## Límites

* La cobertura mide qué líneas se ejecutan, no que las pruebas afirmen lo correcto; por eso la matriz de [trazabilidad](trazabilidad.md)
  enlaza cada requisito con las pruebas concretas que lo verifican.
* No se mide la cobertura de las pruebas contra el proveedor de IA real (`ProveedorRealTests`, omitidas sin `LLM_API_KEY`).
