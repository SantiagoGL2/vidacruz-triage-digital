-- =============================================================================
-- V4: contexto de traza distribuida en el outbox
--   * traceparent : cabecera W3C "traceparent" de la peticion que origino el evento. El publicador del outbox corre en otro
--                   hilo y a otra hora, asi que sin guardarla la traza se cortaria entre la peticion HTTP y Kafka.
--                   Es NULL cuando el evento no nace de una peticion (p. ej. el escalamiento por SLA) o si no hay traza activa.
-- =============================================================================
ALTER TABLE outbox_eventos ADD COLUMN traceparent VARCHAR(100);

COMMENT ON COLUMN outbox_eventos.traceparent IS 'W3C traceparent de la peticion original; se restaura como padre del span de publicacion a Kafka.';
