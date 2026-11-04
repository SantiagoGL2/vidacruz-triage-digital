# Reporte de operación por hora (RF-05)

`GET /triage/reportes/operacion?desde=...&hasta=...` (header `X-API-Key`)

Devuelve, por cada hora del rango, los pacientes registrados, el conteo por nivel de triaje, cuántos fueron atendidos y el
tiempo de espera promedio y p95 (registro → primera entrada a consulta). **Todo el cálculo lo hace PostgreSQL en una sola
consulta** (`triage-service/src/main/resources/db/queries/reporte-operacion.sql`); Java solo enlaza parámetros y mapea filas
(`ReporteOperacionR2dbcAdapter` + `ReporteOperacionMapper`). No se trae ningún registro a memoria para agregarlo.

## Contrato

| Parámetro | Descripción |
|-----------|-------------|
| `desde` | ISO 8601 **con zona u offset** (`2026-11-04T00:00:00-05:00`). Incluido. |
| `hasta` | ISO 8601 con zona u offset. **Excluido**: el rango es `[desde, hasta)`. |

Si el offset lleva `+` hay que codificarlo como `%2B` en la URL (un `+` sin codificar llega como espacio y es un 400).

Errores (400, `application/problem+json`; `errores[0].campo` nombra el parámetro): parámetro ausente, formato inválido (incluye fechas
sin offset), `desde >= hasta`, o rango mayor a `triage.reportes.rango-maximo-dias` (31 por defecto). Sin API key → 401.

```json
{
  "desde": "2026-11-04T00:00:00-05:00", "hasta": "2026-11-04T03:00:00-05:00", "zonaHoraria": "America/Bogota",
  "horas": [
    { "hora": "2026-11-04T00:00:00-05:00", "totalRegistrados": 12,
      "porNivel": { "CRITICO": 1, "URGENTE": 2, "URGENTE_2": 4, "NO_URGENTE": 3, "NO_URGENTE_2": 2 },
      "pacientesAtendidos": 9, "esperaPromedioMinutos": 18.4, "esperaP95Minutos": 41.0 },
    { "hora": "2026-11-04T01:00:00-05:00", "totalRegistrados": 0,
      "porNivel": { "CRITICO": 0, "URGENTE": 0, "URGENTE_2": 0, "NO_URGENTE": 0, "NO_URGENTE_2": 0 },
      "pacientesAtendidos": 0, "esperaPromedioMinutos": null, "esperaP95Minutos": null }
  ]
}
```

Configuración: `triage.reportes.zona-horaria` (`TRIAGE_REPORTES_ZONA`, por defecto `America/Bogota`) y
`triage.reportes.rango-maximo-dias` (`TRIAGE_REPORTES_RANGO_MAXIMO_DIAS`, por defecto 31).

## La consulta, paso a paso

Parámetros con nombre (`:desde`, `:hasta`, `:zona`), nunca concatenación de texto.

1. **`horas`** — `generate_series` genera una fila por cada hora de pared en `:zona` que toca el rango. `AT TIME ZONE :zona`
   pasa el instante a hora local y `date_trunc('hour', …)` lo redondea hacia abajo. El límite superior es `hasta - 1 µs` porque
   `hasta` no está incluido (un `hasta` en punto no genera una hora de más). Esto garantiza **una fila por hora aunque no haya
   pacientes**.
2. **`registros`** — pacientes con `fecha_registro` en `[desde, hasta)`, con su nivel y su hora de registro (misma zona). Usa el
   índice `idx_pacientes_fecha_registro`.
3. **`primera_consulta`** — de la auditoría, la **primera** transición a `EN_CONSULTA` de cada paciente:
   `ROW_NUMBER() OVER (PARTITION BY paciente_id ORDER BY fecha_evento, id)` y nos quedamos con `orden = 1`. Solo se mira a los
   pacientes del paso 2 (`paciente_id IN (SELECT id FROM registros)`), no toda la auditoría.
4. **`espera`** — `LEFT JOIN` registros ↔ primera consulta; los minutos son
   `EXTRACT(EPOCH FROM (consulta - registro)) / 60`, y `NULL` si el paciente aún no entró a consulta.
5. **`agregado`** — `GROUP BY hora`: `COUNT(*)` (total), `COUNT(*) FILTER (WHERE nivel = n)` para cada uno de los 5 niveles,
   `COUNT(minutos)` (atendidos), `AVG(minutos)` y `percentile_cont(0.95) WITHIN GROUP (ORDER BY minutos)`. Los tres últimos
   ignoran los `NULL` por definición.
6. **Resultado** — `horas LEFT JOIN agregado`, `ORDER BY hora`. Las horas sin pacientes salen con `COALESCE(…, 0)` y
   promedio/p95 `NULL`. Los minutos se **redondean a 2 decimales en SQL** (`ROUND(CAST(x AS numeric), 2)`); el mapper no redondea.

`percentile_cont` interpola linealmente: con esperas 10, 20, 30 y 40 min el p95 está en la posición 0.95 × (4 − 1) = 2.85,
es decir `30 + 0.85 × (40 − 30) = 38.5`. Ese es uno de los casos del test.

## Supuestos

1. **La espera se asigna a la hora en que el paciente se registró**, no a la hora en que entró a consulta. Así «promedio de la hora
   13:00» describe a quienes llegaron a las 13:00. Consecuencia: la espera de pacientes recién registrados aún no aparece
   (siguen sin consulta) y el reporte de una hora puede cambiar mientras esos pacientes sean atendidos.
2. **Sin consulta no cuenta en el promedio ni en el p95:** quien pasó directo a `ALTA` o aún espera cuenta en `totalRegistrados`
   y `porNivel`, pero no en `pacientesAtendidos`, ni en el promedio ni en el p95.
3. **La primera entrada a consulta sale de la auditoría**, no de `pacientes.fecha_primera_consulta`. La auditoría es append-only
   (RN-04, trigger en BD) y por tanto la fuente de verdad; la columna es un dato derivado que se escribe junto al cambio de estado.
   Hoy ambas coinciden (hay un test que lo verifica) y el flujo solo permite una entrada a `EN_CONSULTA`; el `ROW_NUMBER` protege
   contra duplicados anómalos (también hay un test).
4. **Horas = horas de pared en la zona configurada.** Si `desde` no cae en punto, la primera hora es parcial (solo cuenta desde
   `desde`); igual la última respecto de `hasta`. La zona por defecto, `America/Bogota`, no tiene horario de verano; con una zona
   que sí lo tenga, la hora repetida del cambio de hora se agrupa en una sola fila.
5. **Redondeo a 2 decimales en SQL** (el ejemplo del enunciado muestra 1; 2 conserva más precisión y es consistente en todos los casos).
6. **Rango máximo configurable** (31 días ≈ 744 filas) para acotar el costo de la consulta y el tamaño de la respuesta.

## Índices y EXPLAIN ANALYZE

Se revisó el plan con datos sintéticos en PostgreSQL 16 (`EXPLAIN (ANALYZE, BUFFERS)`):

* **Dataset A:** 20 000 pacientes / 34 000 filas de auditoría, 70 % con consulta; rango de 2 días (5 760 pacientes, 48 horas).
* **Dataset B:** 220 000 pacientes / 374 000 filas de auditoría; rango de 1 día (576 pacientes) y de 6 horas (144).

Resumen del plan (con el índice de la migración `V3`):

```
Merge Left Join (rows=48)                                    -- horas LEFT JOIN agregado
  CTE registros: Index Scan using idx_pacientes_fecha_registro  (rango sobre fecha_registro, 133 buffers para 5 760 filas)
  Sort(horas)  <- Function Scan on generate_series
  GroupAggregate (rows=48)                                   -- COUNT/FILTER/AVG/percentile_cont por hora
    Sort por hora
      Hash Left Join registros ↔ primera_consulta
        Subquery Scan numerada  Filter: orden = 1            -- ROW_NUMBER()
          WindowAgg  Run Condition: row_number() <= 1
            Merge Join registros.id = a.paciente_id
              Index Only Scan using idx_auditoria_primera_consulta on auditoria_clinica a   (V3)
Execution Time: 16 ms   Buffers: shared hit=243   (dataset A, 2 días)
```

| Escenario | Sin `V3` | Con `V3` |
|-----------|----------|----------|
| A, 2 días | `Seq Scan` sobre toda la auditoría (34 000 filas leídas, 20 000 descartadas): 594 buffers, 18.3 ms | `Index Only Scan` parcial: **243 buffers**, 16.2 ms |
| B, 6 horas | `Index Scan using idx_auditoria_paciente_fecha` con filtro `estado_nuevo`: 696 buffers, 1.4 ms | `Index Only Scan` parcial: 553 buffers, 1.6 ms |
| B, 1 día | igual que arriba: 2 731 buffers, 4.7 ms | `Index Only Scan` parcial: 1 700 buffers (+464 lecturas en frío), 4.7 ms |

* `pacientes`: el filtro por rango de `fecha_registro` ya usa `idx_pacientes_fecha_registro` (creado en `V1`, con `INCLUDE (nivel, …)`); no hizo falta tocarlo.
* `auditoria_clinica`: el índice existente `(paciente_id, fecha_evento)` ya evita recorrer toda la tabla cuando es grande (dataset B), pero no sirve
  de forma consistente con pocas filas (dataset A hizo `Seq Scan`). Se agregó en **`V3__indice_reporte_operacion.sql`** un índice **parcial**
  `idx_auditoria_primera_consulta (paciente_id, fecha_evento, id) WHERE estado_nuevo = 'EN_CONSULTA'`: contiene solo esas filas (≈ una por
  paciente), ya está ordenado como el `ROW_NUMBER` y permite un *index-only scan*. La mejora medida es modesta (≈ 20–60 % menos buffers según el escenario,
  tiempos similares a este tamaño); su valor es que el plan no depende del tamaño de la tabla y que el costo de escritura es
  despreciable (solo se indexan las filas `EN_CONSULTA`).
* Con 20 000 pacientes la consulta tarda entre 1 y 20 ms; los tiempos no son una medición de rendimiento real (datos sintéticos en memoria),
  solo sirven para comprobar el plan.

## Tests

`ReporteOperacionTests` arma un dataset determinista (pacientes y auditoría con timestamps controlados, un día distinto por test) con resultados
calculados a mano: conteo total y por nivel; promedio y p95 exactos (incluida la interpolación de `percentile_cont`); horas vacías en cero y
`null`; sin consulta / alta directa fuera del promedio; solo la primera entrada a consulta; límites del rango (`desde` entra, `hasta` no);
agrupación en hora de Bogotá (23:30 local ≠ hora UTC del día siguiente); que la consulta devuelva una fila por hora del rango (72 filas para 3 días,
aunque haya 30 pacientes: no se agrega en memoria); coherencia auditoría ↔ `fecha_primera_consulta`; forma del JSON y las validaciones 400.
