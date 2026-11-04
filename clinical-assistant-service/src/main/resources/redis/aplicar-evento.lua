-- =============================================================================
-- Aplica UN evento de triaje a la vista de urgencias, de forma atomica e idempotente.
-- Redis ejecuta el script completo sin intercalar otros comandos: verificar el eventId, tocar el paciente,
-- ajustar los contadores y marcar el eventId como procesado ocurren como una sola operacion.
--
-- KEYS[1] evento        string  marca "eventId ya procesado" (con TTL)
-- KEYS[2] paciente      hash    nivel, estado, escalado, fechaRegistro (un hash por paciente ACTIVO)
-- KEYS[3] contadores    hash    CRITICO..NO_URGENTE_2 y "escalados" (activos)
-- KEYS[4] ultimo-evento string  occurredAt del evento mas reciente aplicado, en microsegundos desde epoch
-- ARGV[1] operacion     REGISTRAR | CONSULTA | ESCALAR | ALTA
-- ARGV[2] nivel         solo REGISTRAR
-- ARGV[3] fechaRegistro solo REGISTRAR (ISO 8601)
-- ARGV[4] ocurridoMicros occurredAt del evento
-- ARGV[5] ttlSegundos   TTL de la marca de eventId
--
-- Devuelve {codigo, CRITICO, URGENTE, URGENTE_2, NO_URGENTE, NO_URGENTE_2, escalados, ultimoMicros}
-- codigo: 1 aplicado | 2 duplicado | 3 paciente desconocido (ignorado) | 4 ya registrado (ignorado) | 5 sin cambio
-- Los contadores nunca quedan por debajo de cero.
-- =============================================================================
local NIVELES = { 'CRITICO', 'URGENTE', 'URGENTE_2', 'NO_URGENTE', 'NO_URGENTE_2' }

local function resumen(codigo)
  local r = { codigo }
  for _, nivel in ipairs(NIVELES) do
    r[#r + 1] = tonumber(redis.call('HGET', KEYS[3], nivel)) or 0
  end
  r[#r + 1] = tonumber(redis.call('HGET', KEYS[3], 'escalados')) or 0
  r[#r + 1] = tonumber(redis.call('GET', KEYS[4])) or 0
  return r
end

-- Resta 1 a un campo del hash de contadores sin bajar de cero
local function restar(campo)
  if redis.call('HINCRBY', KEYS[3], campo, -1) < 0 then
    redis.call('HSET', KEYS[3], campo, 0)
  end
end

-- 1) idempotencia por eventId
if redis.call('EXISTS', KEYS[1]) == 1 then
  return resumen(2)
end

local operacion = ARGV[1]
local existe = redis.call('EXISTS', KEYS[2]) == 1
local codigo = 1

if operacion == 'REGISTRAR' then
  if existe then
    codigo = 4                                   -- mismo paciente con otro eventId: no suma dos veces
  else
    redis.call('HSET', KEYS[2], 'nivel', ARGV[2], 'estado', 'REGISTRADO', 'escalado', '0', 'fechaRegistro', ARGV[3])
    redis.call('HINCRBY', KEYS[3], ARGV[2], 1)
  end
elseif not existe then
  codigo = 3                                     -- consulta / escalamiento / alta de un paciente que no esta en la vista
elseif operacion == 'CONSULTA' then
  redis.call('HSET', KEYS[2], 'estado', 'EN_CONSULTA')
elseif operacion == 'ESCALAR' then
  if redis.call('HGET', KEYS[2], 'escalado') == '1' then
    codigo = 5                                   -- ya estaba escalado: no cuenta dos veces
  else
    redis.call('HSET', KEYS[2], 'escalado', '1')
    redis.call('HINCRBY', KEYS[3], 'escalados', 1)
  end
elseif operacion == 'ALTA' then
  local nivel = redis.call('HGET', KEYS[2], 'nivel')
  local escalado = redis.call('HGET', KEYS[2], 'escalado')
  redis.call('DEL', KEYS[2])
  restar(nivel)                                  -- el nivel que el paciente tenia EN LA VISTA, no el del evento
  if escalado == '1' then
    restar('escalados')
  end
end

-- 2) ultimo evento aplicado = el de occurredAt mas reciente (entre particiones no llegan en orden)
local actual = tonumber(redis.call('GET', KEYS[4]))
local nuevo = tonumber(ARGV[4])
if actual == nil or nuevo > actual then
  redis.call('SET', KEYS[4], string.format('%.0f', nuevo))
end

-- 3) marcar el eventId como procesado (tambien los ignorados: reentregarlos no cambia nada)
redis.call('SET', KEYS[1], '1', 'EX', tonumber(ARGV[5]))

return resumen(codigo)
