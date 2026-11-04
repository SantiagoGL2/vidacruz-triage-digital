-- =============================================================================
-- V1: esquema inicial del servicio de Triage
--   * pacientes          -> estado actual (optimistic locking con "version")
--   * auditoria_clinica  -> bitacora inmutable (RN-04), bloqueada por trigger
--   * outbox_eventos     -> Transactional Outbox hacia Kafka (triaje.eventos)
-- Convenciones: tiempos en TIMESTAMPTZ (UTC); nivel de triaje 1..5 (RN-01).
--   1=CRITICO 2=URGENTE 3=URGENTE_2 4=NO_URGENTE 5=NO_URGENTE_2
-- =============================================================================

CREATE TABLE pacientes (
    id                          UUID            PRIMARY KEY,
    nombre_completo             VARCHAR(100)    NOT NULL,
    edad                        SMALLINT        NOT NULL,
    frecuencia_cardiaca         SMALLINT        NOT NULL,   -- bpm
    presion_sistolica           SMALLINT        NOT NULL,   -- mmHg
    presion_diastolica          SMALLINT        NOT NULL,   -- mmHg
    spo2                        SMALLINT        NOT NULL,   -- %
    temperatura                 NUMERIC(3, 1)   NOT NULL,   -- grados C
    dolor                       SMALLINT        NOT NULL,   -- escala 0-10
    gcs                         SMALLINT        NOT NULL,   -- Glasgow 3-15
    queja_principal             VARCHAR(500)    NOT NULL,
    nivel                       SMALLINT        NOT NULL,
    sla_minutos                 INTEGER         NOT NULL,   -- SLA vigente al registrar (RN-02), inmune a cambios de configuracion
    estado                      VARCHAR(20)     NOT NULL DEFAULT 'REGISTRADO',
    escalado                    BOOLEAN         NOT NULL DEFAULT FALSE,
    fecha_escalamiento          TIMESTAMPTZ,
    fecha_registro              TIMESTAMPTZ     NOT NULL,
    fecha_primera_consulta      TIMESTAMPTZ,
    fecha_ultimo_cambio_estado  TIMESTAMPTZ     NOT NULL,
    version                     BIGINT          NOT NULL DEFAULT 0,  -- optimistic locking

    -- Validaciones de datos (RF-01) como ultima linea de defensa
    CONSTRAINT ck_pacientes_nombre      CHECK (char_length(nombre_completo) BETWEEN 3 AND 100),
    CONSTRAINT ck_pacientes_edad        CHECK (edad BETWEEN 0 AND 120),
    CONSTRAINT ck_pacientes_fc          CHECK (frecuencia_cardiaca BETWEEN 20 AND 250),
    CONSTRAINT ck_pacientes_sistolica   CHECK (presion_sistolica BETWEEN 60 AND 260),
    CONSTRAINT ck_pacientes_diastolica  CHECK (presion_diastolica BETWEEN 30 AND 160),
    CONSTRAINT ck_pacientes_presion     CHECK (presion_diastolica < presion_sistolica),
    CONSTRAINT ck_pacientes_spo2        CHECK (spo2 BETWEEN 70 AND 100),
    CONSTRAINT ck_pacientes_temperatura CHECK (temperatura BETWEEN 34.0 AND 42.0),
    CONSTRAINT ck_pacientes_dolor       CHECK (dolor BETWEEN 0 AND 10),
    CONSTRAINT ck_pacientes_gcs         CHECK (gcs BETWEEN 3 AND 15),

    -- Dominio
    CONSTRAINT ck_pacientes_nivel       CHECK (nivel BETWEEN 1 AND 5),
    CONSTRAINT ck_pacientes_sla         CHECK (sla_minutos > 0),
    CONSTRAINT ck_pacientes_estado      CHECK (estado IN ('REGISTRADO', 'EN_CONSULTA', 'ALTA')),

    -- Coherencia temporal y de flags
    CONSTRAINT ck_pacientes_escalado_fecha
        CHECK (escalado = (fecha_escalamiento IS NOT NULL)),
    CONSTRAINT ck_pacientes_consulta_fecha
        CHECK (estado <> 'EN_CONSULTA' OR fecha_primera_consulta IS NOT NULL),
    CONSTRAINT ck_pacientes_registrado_sin_consulta
        CHECK (estado <> 'REGISTRADO' OR fecha_primera_consulta IS NULL),
    CONSTRAINT ck_pacientes_orden_fechas
        CHECK (fecha_primera_consulta IS NULL OR fecha_primera_consulta >= fecha_registro)
);

COMMENT ON COLUMN pacientes.version IS 'Optimistic locking (@Version de Spring Data R2DBC).';
COMMENT ON COLUMN pacientes.sla_minutos IS 'SLA en minutos congelado al registrar; el scheduler lo compara contra fecha_registro.';

-- Scheduler de SLA (RF-03): pacientes REGISTRADO y no escalados, ordenados por antiguedad.
CREATE INDEX idx_pacientes_sla_scheduler ON pacientes (estado, escalado, fecha_registro);
-- Reporte por hora (RF-05): rango por fecha_registro; incluye nivel y primera consulta para index-only scans.
CREATE INDEX idx_pacientes_fecha_registro ON pacientes (fecha_registro) INCLUDE (nivel, fecha_primera_consulta);

-- -----------------------------------------------------------------------------
-- Auditoria clinica (RN-04): append-only
-- -----------------------------------------------------------------------------
CREATE TABLE auditoria_clinica (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    paciente_id     UUID            NOT NULL REFERENCES pacientes (id),
    fecha_evento    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    estado_anterior VARCHAR(20),                          -- NULL en el registro inicial
    estado_nuevo    VARCHAR(20)     NOT NULL,
    tipo_evento     VARCHAR(30)     NOT NULL,
    detalle         TEXT,

    CONSTRAINT ck_auditoria_estado_anterior CHECK (estado_anterior IS NULL OR estado_anterior IN ('REGISTRADO', 'EN_CONSULTA', 'ALTA')),
    CONSTRAINT ck_auditoria_estado_nuevo    CHECK (estado_nuevo IN ('REGISTRADO', 'EN_CONSULTA', 'ALTA')),
    CONSTRAINT ck_auditoria_tipo_evento     CHECK (tipo_evento IN ('PACIENTE_REGISTRADO', 'PACIENTE_EN_CONSULTA', 'PACIENTE_ESCALADO', 'PACIENTE_ALTA'))
);

CREATE INDEX idx_auditoria_paciente_fecha ON auditoria_clinica (paciente_id, fecha_evento);

-- Red de seguridad de RN-03: a lo sumo UN escalamiento auditado por paciente, aunque falle la logica de aplicacion.
CREATE UNIQUE INDEX uq_auditoria_un_escalamiento ON auditoria_clinica (paciente_id) WHERE tipo_evento = 'PACIENTE_ESCALADO';

CREATE FUNCTION fn_auditoria_inmutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'auditoria_clinica es inmutable: operacion % no permitida (RN-04)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_auditoria_sin_update_delete
    BEFORE UPDATE OR DELETE ON auditoria_clinica
    FOR EACH ROW EXECUTE FUNCTION fn_auditoria_inmutable();

CREATE TRIGGER trg_auditoria_sin_truncate
    BEFORE TRUNCATE ON auditoria_clinica
    FOR EACH STATEMENT EXECUTE FUNCTION fn_auditoria_inmutable();

-- -----------------------------------------------------------------------------
-- Transactional Outbox: se escribe en la MISMA transaccion que el cambio de estado
-- -----------------------------------------------------------------------------
CREATE TABLE outbox_eventos (
    id              UUID            PRIMARY KEY,          -- eventId del envelope
    aggregate_id    UUID            NOT NULL,             -- paciente_id; clave de particion Kafka (orden por paciente)
    event_type      VARCHAR(40)     NOT NULL,
    payload         JSONB           NOT NULL,             -- envelope completo
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    intentos        INTEGER         NOT NULL DEFAULT 0,

    CONSTRAINT ck_outbox_event_type CHECK (event_type IN ('paciente_registrado', 'paciente_en_consulta', 'paciente_escalado', 'paciente_alta')),
    CONSTRAINT ck_outbox_intentos   CHECK (intentos >= 0)
);

-- Cola de pendientes: indice parcial pequeno aunque la tabla crezca (FOR UPDATE SKIP LOCKED lo recorre en orden).
CREATE INDEX idx_outbox_pendientes ON outbox_eventos (created_at) WHERE published_at IS NULL;
