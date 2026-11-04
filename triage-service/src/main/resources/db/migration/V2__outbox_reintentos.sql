-- =============================================================================
-- V2: datos para el backoff del publicador del outbox
--   * ultimo_intento_at : cuando se intento publicar por ultima vez (base del backoff exponencial)
--   * ultimo_error      : motivo del ultimo fallo, para diagnostico (truncado a 500)
-- =============================================================================
ALTER TABLE outbox_eventos
    ADD COLUMN ultimo_intento_at TIMESTAMPTZ,
    ADD COLUMN ultimo_error      VARCHAR(500);

COMMENT ON COLUMN outbox_eventos.ultimo_intento_at IS 'Instante del ultimo intento de publicacion; el siguiente espera backoff exponencial segun intentos.';
