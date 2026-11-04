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
