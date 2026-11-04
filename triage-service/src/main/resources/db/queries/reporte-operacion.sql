-- =============================================================================
-- RF-05: reporte de operacion por hora, en UNA sola consulta.
-- Todo el calculo (conteos, promedio, p95) lo hace PostgreSQL; Java solo mapea filas.
--
-- Parametros con nombre (nunca se concatena texto):
--   :desde  TIMESTAMPTZ  inicio del rango (incluido)
--   :hasta  TIMESTAMPTZ  fin del rango (excluido)  -> rango semiabierto [desde, hasta)
--   :zona   TEXT         zona horaria del reporte (ej. America/Bogota)
--
-- Supuestos (ver docs/reporte-operacion.md):
--   * El tiempo de espera se asigna a la hora en que el paciente SE REGISTRO.
--   * Sin primera consulta (alta directa o aun esperando): cuenta en el total y por nivel,
--     pero NO en el promedio ni en el p95 (espera = NULL, y los agregados ignoran NULL).
--   * "Hora" = hora de pared en la zona configurada; la primera/ultima pueden ser parciales
--     si desde/hasta no caen en punto.
-- =============================================================================
WITH
-- 1) horas: una fila por cada hora de pared (en :zona) que toca el rango, aunque no haya pacientes.
--    "AT TIME ZONE :zona" convierte el instante a hora local (TIMESTAMP sin zona) y date_trunc la
--    redondea hacia abajo. El limite superior usa (hasta - 1 microsegundo) porque hasta NO esta incluido.
horas AS (
    SELECT hora_local
      FROM generate_series(
               date_trunc('hour', CAST(:desde AS timestamptz) AT TIME ZONE :zona),
               date_trunc('hour', (CAST(:hasta AS timestamptz) - INTERVAL '1 microsecond') AT TIME ZONE :zona),
               INTERVAL '1 hour') AS hora_local
),

-- 2) registros: pacientes registrados en [desde, hasta), con su hora de registro (misma zona).
--    Usa el indice idx_pacientes_fecha_registro (rango sobre fecha_registro).
registros AS (
    SELECT p.id,
           p.nivel,
           p.fecha_registro,
           date_trunc('hour', p.fecha_registro AT TIME ZONE :zona) AS hora_local
      FROM pacientes p
     WHERE p.fecha_registro >= CAST(:desde AS timestamptz)
       AND p.fecha_registro <  CAST(:hasta AS timestamptz)
),

-- 3) primera_consulta: la PRIMERA transicion a EN_CONSULTA de cada paciente segun la auditoria.
--    ROW_NUMBER() numera las entradas de cada paciente por fecha y nos quedamos con la 1.
--    La auditoria (append-only, RN-04) es la fuente de verdad: pacientes.fecha_primera_consulta es un
--    dato derivado que se escribe junto al cambio de estado; la auditoria no se puede editar ni borrar.
--    Solo se mira a los pacientes del rango (IN sobre registros) para no recorrer toda la auditoria.
primera_consulta AS (
    SELECT paciente_id, fecha_evento
      FROM (SELECT a.paciente_id,
                   a.fecha_evento,
                   ROW_NUMBER() OVER (PARTITION BY a.paciente_id ORDER BY a.fecha_evento, a.id) AS orden
              FROM auditoria_clinica a
             WHERE a.estado_nuevo = 'EN_CONSULTA'
               AND a.paciente_id IN (SELECT id FROM registros)) numerada
     WHERE orden = 1
),

-- 4) espera: minutos entre el registro y la primera consulta; NULL si aun no entro a consulta.
espera AS (
    SELECT r.hora_local,
           r.nivel,
           EXTRACT(EPOCH FROM (c.fecha_evento - r.fecha_registro)) / 60.0 AS minutos
      FROM registros r
      LEFT JOIN primera_consulta c ON c.paciente_id = r.id
),

-- 5) agregado por hora: conteos con FILTER por nivel (1..5) y estadisticos sobre la espera.
--    COUNT(minutos), AVG y percentile_cont ignoran los NULL. percentile_cont(0.95) interpola
--    linealmente entre los dos valores vecinos cuando el percentil no cae en un dato exacto.
agregado AS (
    SELECT hora_local,
           COUNT(*)                                                  AS total_registrados,
           COUNT(*) FILTER (WHERE nivel = 1)                         AS nivel_1,
           COUNT(*) FILTER (WHERE nivel = 2)                         AS nivel_2,
           COUNT(*) FILTER (WHERE nivel = 3)                         AS nivel_3,
           COUNT(*) FILTER (WHERE nivel = 4)                         AS nivel_4,
           COUNT(*) FILTER (WHERE nivel = 5)                         AS nivel_5,
           COUNT(minutos)                                            AS pacientes_atendidos,
           AVG(minutos)                                              AS espera_promedio,
           percentile_cont(0.95) WITHIN GROUP (ORDER BY minutos)     AS espera_p95
      FROM espera
     GROUP BY hora_local
)

-- 6) resultado: LEFT JOIN de las horas con el agregado (las horas vacias salen en cero y con
--    promedio/p95 NULL). Minutos redondeados a 2 decimales aqui, en SQL.
SELECT h.hora_local AT TIME ZONE :zona                                               AS hora,
       COALESCE(a.total_registrados, 0)                                              AS total_registrados,
       COALESCE(a.nivel_1, 0)                                                        AS nivel_1,
       COALESCE(a.nivel_2, 0)                                                        AS nivel_2,
       COALESCE(a.nivel_3, 0)                                                        AS nivel_3,
       COALESCE(a.nivel_4, 0)                                                        AS nivel_4,
       COALESCE(a.nivel_5, 0)                                                        AS nivel_5,
       COALESCE(a.pacientes_atendidos, 0)                                            AS pacientes_atendidos,
       CAST(ROUND(CAST(a.espera_promedio AS numeric), 2) AS double precision)        AS espera_promedio_minutos,
       CAST(ROUND(CAST(a.espera_p95 AS numeric), 2) AS double precision)             AS espera_p95_minutos
  FROM horas h
  LEFT JOIN agregado a ON a.hora_local = h.hora_local
 ORDER BY h.hora_local
