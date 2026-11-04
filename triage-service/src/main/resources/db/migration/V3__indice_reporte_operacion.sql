-- =============================================================================
-- V3: indice para el reporte de operacion (RF-05)
-- La consulta busca, solo para los pacientes del rango, la PRIMERA entrada a EN_CONSULTA en la auditoria.
-- Un indice parcial (solo filas EN_CONSULTA) es pequeno y ya viene ordenado por (paciente_id, fecha_evento, id),
-- justo el orden del ROW_NUMBER() OVER (PARTITION BY paciente_id ORDER BY fecha_evento, id); permite un
-- index-only scan por paciente en lugar de recorrer toda la auditoria.
-- =============================================================================
CREATE INDEX idx_auditoria_primera_consulta
    ON auditoria_clinica (paciente_id, fecha_evento, id)
    WHERE estado_nuevo = 'EN_CONSULTA';
