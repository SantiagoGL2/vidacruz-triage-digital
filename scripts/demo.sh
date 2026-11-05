#!/usr/bin/env bash
# Demo de punta a punta de VidaCruz Triage contra local o contra Azure (HTTPS). Equivalente a scripts/demo.ps1.
#
# Recorrido: 1 salud · 2 registrar E1-E6 y comparar con el nivel esperado · 3 validacion (400) · 4 transiciones (200 y 409) ·
# 5 estado de urgencias antes/despues de un alta (consistencia eventual) · 6 escalamiento por SLA con alerta SSE (solo si el SLA
# es corto) · 7 reporte de operacion · 8 las 3 consultas de ejemplo al asistente · 9 resumen.
#
# Las URLs y las API keys NUNCA van en el codigo: variables de entorno (TRIAGE_URL, ASISTENTE_URL, TRIAGE_API_KEY,
# ASSISTANT_API_KEY) o un archivo KEY=VALUE con ARCHIVO_ENV (p. ej. .env o infra/.env.azure). Prioridad: entorno > archivo > defecto
# (el defecto solo existe para las URLs: localhost). Requiere bash >= 4 y curl >= 7.55.
#
# Opciones por entorno:
#   ARCHIVO_ENV=<ruta>               carga claves/URLs de un archivo KEY=VALUE
#   ESCALAMIENTO=auto|si|no          auto (defecto): paso 6 solo si el SLA de CRITICO cabe en ESPERA_MAXIMA_ESCALAMIENTO
#   ESPERA_MAXIMA_ESCALAMIENTO=150   segundos
#   INSEGURO=1                       no verificar el certificado TLS (p. ej. con el emisor staging de Let's Encrypt)
#   NO_COLOR=1                       sin colores
#
# Ejemplos:
#   ARCHIVO_ENV=.env ./scripts/demo.sh                                   # local
#   ARCHIVO_ENV=infra/.env.azure TRIAGE_URL=https://<triage>.eastus.cloudapp.azure.com \
#     ASISTENTE_URL=https://<asistente>.eastus.cloudapp.azure.com ./scripts/demo.sh     # Azure
set -u

# ---- configuracion: entorno > archivo > defecto ----
if [ -n "${ARCHIVO_ENV:-}" ]; then
  [ -f "$ARCHIVO_ENV" ] || { echo "No existe el archivo $ARCHIVO_ENV" >&2; exit 2; }
  while IFS='=' read -r k v; do
    case "$k" in ''|'#'*) continue ;; esac
    v="${v%$'\r'}"; v="${v%\"}"; v="${v#\"}"
    [ -z "${!k:-}" ] && export "$k=$v"
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ARCHIVO_ENV")
fi
TRIAGE_URL="${TRIAGE_URL:-http://localhost:8081}"; TRIAGE_URL="${TRIAGE_URL%/}"
ASISTENTE_URL="${ASISTENTE_URL:-http://localhost:8082}"; ASISTENTE_URL="${ASISTENTE_URL%/}"
ESCALAMIENTO="${ESCALAMIENTO:-auto}"
ESPERA_MAXIMA_ESCALAMIENTO="${ESPERA_MAXIMA_ESCALAMIENTO:-150}"
if [ -z "${TRIAGE_API_KEY:-}" ] || [ -z "${ASSISTANT_API_KEY:-}" ]; then
  echo "Faltan TRIAGE_API_KEY y/o ASSISTANT_API_KEY (variables de entorno o ARCHIVO_ENV)." >&2; exit 2
fi
CURL_EXTRA=(); [ "${INSEGURO:-0}" = "1" ] && CURL_EXTRA+=(-k)

# ---- salida ----
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then C_T=$'\033[36m'; C_OK=$'\033[32m'; C_KO=$'\033[31m'; C_AV=$'\033[33m'; C_GR=$'\033[90m'; C_0=$'\033[0m'
else C_T=''; C_OK=''; C_KO=''; C_AV=''; C_GR=''; C_0=''; fi
OK=0; FALLO=0; OMITIDO=0
titulo() { printf '\n%s[%s] %s%s\n' "$C_T" "$1" "$2" "$C_0"; }
explica() { printf '%s    %s%s\n' "$C_GR" "$1" "$C_0"; }
dato() { printf '    %s\n' "$1"; }
pasa() { OK=$((OK+1)); printf '%s    OK     %s%s\n' "$C_OK" "$1" "$C_0"; }
falla() { FALLO=$((FALLO+1)); printf '%s    FALLO  %s%s\n' "$C_KO" "$1" "$C_0"; }
omite() { OMITIDO=$((OMITIDO+1)); printf '%s    OMITIDO  %s%s\n' "$C_AV" "$1" "$C_0"; }

# ---- HTTP: req METODO URL CLAVE [CUERPO] -> CODIGO, CUERPO ----
TMP="$(mktemp -d)"; SSE_PID=''
limpiar() { [ -n "$SSE_PID" ] && kill "$SSE_PID" 2>/dev/null; rm -rf "$TMP"; }
trap limpiar EXIT
req() {
  local metodo="$1" url="$2" clave="$3" cuerpo="${4:-}"
  local args=(-s -m 60 -o "$TMP/cuerpo" -w '%{http_code}' -X "$metodo" "${CURL_EXTRA[@]}")
  if [ -n "$clave" ]; then printf 'X-API-Key: %s\n' "$clave" > "$TMP/cab"; args+=(-H "@$TMP/cab"); fi
  [ -n "$cuerpo" ] && args+=(-H 'Content-Type: application/json' --data-binary "$cuerpo")
  CODIGO="$(curl "${args[@]}" "$url" 2>/dev/null)" || CODIGO=0
  CUERPO="$(cat "$TMP/cuerpo" 2>/dev/null)"
}
# json_get "<json>" clave -> primer valor de esa clave (cadena o numero/booleano) en JSON plano
json_get() { printf '%s' "$1" | sed -n "s/.*\"$2\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1; }
# json_texto "<json>" clave -> valor de texto completo (admite comas y comillas escapadas), p. ej. "respuesta" o "detail"
json_texto() { printf '%s' "$1" | sed -n 's/.*"'"$2"'":"\(\([^"\\]\|\\.\)*\)".*/\1/p' | head -n1; }
estado_total() { req GET "$ASISTENTE_URL/asistente/estado-urgencias" "$ASSISTANT_API_KEY"; json_get "$CUERPO" totalActivos; }
esperar_hasta() { # esperar_hasta <segundos> <comando...>
  local limite=$((SECONDS + $1)); shift
  while [ "$SECONDS" -lt "$limite" ]; do "$@" && return 0; sleep 1; done; return 1
}

# ---- datos: E1-E6 del enunciado (sin tildes: el curl de Windows no siempre envia UTF-8) ----
# id|fc|sistolica|diastolica|spo2|temp|dolor|gcs|queja|nivel esperado|sla esperado
EJEMPLOS=(
  'E1|130|180|110|97|36.8|8|15|Dolor toracico|CRITICO|5'
  'E2|95|150|95|91|37.0|4|15|Dificultad para respirar|URGENTE|10'
  'E3|88|130|85|98|38.2|6|15|Fiebre|URGENTE_2|30'
  'E4|72|120|80|99|36.5|2|15|Herida por cortar|NO_URGENTE|60'
  'E5|70|115|75|99|36.4|0|15|Renovacion de recetario|NO_URGENTE_2|120'
  'E6|35|80|50|85|35.9|0|6|Inconsciente|CRITICO|5')
cuerpo_paciente() { # id fc sis dia spo2 temp dolor gcs queja [nombre]
  printf '{"nombreCompleto":"%s","edad":45,"frecuenciaCardiaca":%s,"presionSistolica":%s,"presionDiastolica":%s,"spo2":%s,"temperatura":%s,"dolor":%s,"gcs":%s,"quejaPrincipal":"%s"}' \
    "${10:-Paciente $1 (demo)}" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9"
}
fecha_utc() { date -u -d "$1" +%Y-%m-%dT%H:%M:%S%%2B00:00 2>/dev/null || date -u -v"$2" +%Y-%m-%dT%H:%M:%S%%2B00:00; }

echo "VidaCruz Triage - demo de punta a punta"
dato "triage     : $TRIAGE_URL"
dato "asistente  : $ASISTENTE_URL"
dato "(las API keys se toman del entorno o de un archivo; no se muestran)"
INICIO_S=$(date -u +%s)

# ============================================================================================================ 1
titulo 1 "Salud de ambos servicios"
explica "GET /actuator/health/readiness es publico: dice si el servicio puede recibir trafico (triage: base de datos; asistente: Redis e indice de protocolos)."
for par in "triage-service|$TRIAGE_URL" "clinical-assistant-service|$ASISTENTE_URL"; do
  nombre="${par%%|*}"; url="${par#*|}"
  req GET "$url/actuator/health/readiness" ""
  estado="$(json_get "$CUERPO" status)"
  if [ "$CODIGO" = 200 ] && [ "$estado" = "UP" ]; then pasa "$nombre readiness = UP"
  else falla "$nombre readiness = ${estado:-?} (HTTP $CODIGO). Si es el asistente: revisa LLM_API_KEY (modo degradado) o espera a que termine de indexar."; fi
done
ACTIVOS_ANTES="$(estado_total)"; ACTIVOS_ANTES="${ACTIVOS_ANTES:-0}"
dato "pacientes activos en la vista de urgencias antes de empezar: $ACTIVOS_ANTES"

# ============================================================================================================ 2
titulo 2 "Registrar los pacientes de ejemplo E1-E6 y comparar el nivel con el esperado"
explica 'POST /triage/pacientes clasifica con reglas objetivas (RN-01) y fija el SLA (RN-02). Se compara con la columna "Nivel esperado" del enunciado.'
declare -A IDS=(); REGISTRADOS=0
for fila in "${EJEMPLOS[@]}"; do
  IFS='|' read -r id fc sis dia spo2 temp dolor gcs queja esperado sla <<< "$fila"
  req POST "$TRIAGE_URL/triage/pacientes" "$TRIAGE_API_KEY" "$(cuerpo_paciente "$id" "$fc" "$sis" "$dia" "$spo2" "$temp" "$dolor" "$gcs" "$queja")"
  if [ "$CODIGO" != 201 ]; then falla "$id: HTTP $CODIGO $CUERPO"; continue; fi
  IDS[$id]="$(json_get "$CUERPO" id)"; REGISTRADOS=$((REGISTRADOS+1))
  nivel="$(json_get "$CUERPO" nivel)"; sla_real="$(json_get "$CUERPO" slaMinutos)"
  nota=''; [ "$sla_real" != "$sla" ] && nota=" (SLA distinto del enunciado = $sla: configuracion)"
  if [ "$nivel" = "$esperado" ]; then pasa "$(printf '%s: %-12s SLA %3s min  (esperado %s)%s' "$id" "$nivel" "$sla_real" "$esperado" "$nota")"
  else falla "$id: nivel $nivel, se esperaba $esperado"; fi
done
SLA_CRITICO=5
if [ -n "${IDS[E1]:-}" ]; then req GET "$TRIAGE_URL/triage/pacientes/${IDS[E1]}" "$TRIAGE_API_KEY"; SLA_CRITICO="$(json_get "$CUERPO" slaMinutos)"; fi

# ============================================================================================================ 3
titulo 3 "Validacion: datos fuera de rango"
explica "Un paciente con valores imposibles recibe un unico 400 que lista TODOS los campos invalidos (nunca se guarda)."
req POST "$TRIAGE_URL/triage/pacientes" "$TRIAGE_API_KEY" '{"nombreCompleto":"Ab","edad":150,"frecuenciaCardiaca":300,"presionSistolica":120,"presionDiastolica":130,"spo2":120,"temperatura":36.5,"dolor":11,"gcs":15,"quejaPrincipal":"Prueba"}'
CAMPOS="$(printf '%s' "$CUERPO" | grep -o '"campo":"[^"]*"' | cut -d'"' -f4 | paste -sd, -)"
NCAMPOS="$(printf '%s' "$CUERPO" | grep -o '"campo":"[^"]*"' | wc -l | tr -d ' ')"
if [ "$CODIGO" = 400 ] && [ "$NCAMPOS" -ge 5 ]; then pasa "HTTP 400 con $NCAMPOS campos invalidos: ${CAMPOS//,/, }"
else falla "se esperaba 400 con varios campos; HTTP $CODIGO $CUERPO"; fi

# ============================================================================================================ 4
titulo 4 "Transiciones de estado: una valida y una invalida"
explica "REGISTRADO -> EN_CONSULTA es valida (200). Volver a REGISTRADO no lo es (409 con mensaje descriptivo). Cada cambio queda auditado."
if [ -n "${IDS[E2]:-}" ]; then
  req PATCH "$TRIAGE_URL/triage/pacientes/${IDS[E2]}/estado" "$TRIAGE_API_KEY" '{"estado":"EN_CONSULTA"}'
  if [ "$CODIGO" = 200 ] && [ "$(json_get "$CUERPO" estado)" = EN_CONSULTA ]; then pasa "E2: REGISTRADO -> EN_CONSULTA = HTTP 200"; else falla "E2: HTTP $CODIGO $CUERPO"; fi
  req PATCH "$TRIAGE_URL/triage/pacientes/${IDS[E2]}/estado" "$TRIAGE_API_KEY" '{"estado":"REGISTRADO"}'
  if [ "$CODIGO" = 409 ]; then pasa "E2: EN_CONSULTA -> REGISTRADO = HTTP 409: $(json_texto "$CUERPO" detail)"; else falla "se esperaba 409; HTTP $CODIGO $CUERPO"; fi
else falla "sin E2 registrado"; fi

# ============================================================================================================ 5
titulo 5 "Estado de urgencias en el asistente, antes y despues de un alta"
explica "El asistente NO llama a triage: reconstruye este estado consumiendo los eventos de Kafka (consistencia eventual), por eso se espera unos segundos."
ESPERADO_ANTES=$((ACTIVOS_ANTES + REGISTRADOS))
igual_a() { [ "$(estado_total)" = "$1" ]; }
llego=0; esperar_hasta 25 igual_a "$ESPERADO_ANTES" && llego=1
req GET "$ASISTENTE_URL/asistente/estado-urgencias" "$ASSISTANT_API_KEY"; ANTES="$CUERPO"
POR_NIVEL_ANTES="$(printf '%s' "$ANTES" | grep -o '"porNivel":{[^}]*}' | cut -d: -f2-)"
NU_ANTES="$(printf '%s' "$ANTES" | grep -o '"NO_URGENTE":[0-9]*' | cut -d: -f2)"
if [ "$llego" = 1 ]; then pasa "ANTES : activos = $(json_get "$ANTES" totalActivos)  porNivel = $POR_NIVEL_ANTES"
else falla "la vista no llego a $ESPERADO_ANTES activos (tiene $(json_get "$ANTES" totalActivos))"; fi
if [ -n "${IDS[E4]:-}" ]; then
  req PATCH "$TRIAGE_URL/triage/pacientes/${IDS[E4]}/estado" "$TRIAGE_API_KEY" '{"estado":"ALTA"}'
  dato "alta directa de E4 (NO_URGENTE): HTTP $CODIGO"
  OBJETIVO=$(( $(json_get "$ANTES" totalActivos) - 1 ))
  bajo=0; esperar_hasta 25 igual_a "$OBJETIVO" && bajo=1
  req GET "$ASISTENTE_URL/asistente/estado-urgencias" "$ASSISTANT_API_KEY"; DESPUES="$CUERPO"
  NU_DESPUES="$(printf '%s' "$DESPUES" | grep -o '"NO_URGENTE":[0-9]*' | cut -d: -f2)"
  if [ "$bajo" = 1 ] && [ "$NU_DESPUES" = "$((NU_ANTES - 1))" ]; then
    pasa "DESPUES: activos = $(json_get "$DESPUES" totalActivos)  porNivel = $(printf '%s' "$DESPUES" | grep -o '"porNivel":{[^}]*}' | cut -d: -f2-)  (NO_URGENTE $NU_ANTES -> $NU_DESPUES)"
  else falla "la vista no refleja el alta (activos $(json_get "$DESPUES" totalActivos), esperados $OBJETIVO)"; fi
else falla "sin E4 registrado"; fi

# ============================================================================================================ 6
titulo 6 "Escalamiento por SLA y alerta en vivo (SSE)"
explica "Un paciente REGISTRADO que supera su SLA se escala solo (RF-03) y se avisa al equipo medico por SSE (RF-06). SLA real de CRITICO: 5 min."
case "$ESCALAMIENTO" in si) EJECUTAR=1 ;; no) EJECUTAR=0 ;; *) [ $((SLA_CRITICO * 60)) -le "$ESPERA_MAXIMA_ESCALAMIENTO" ] && EJECUTAR=1 || EJECUTAR=0 ;; esac
if [ "$EJECUTAR" != 1 ]; then
  if [ "$ESCALAMIENTO" = no ]; then motivo="pedido con ESCALAMIENTO=no"; else motivo="el SLA de CRITICO es $SLA_CRITICO min (> $ESPERA_MAXIMA_ESCALAMIENTO s de espera maxima); en este entorno el escalamiento tardaria demasiado"; fi
  omite "paso omitido: $motivo."
  explica 'Para verlo: en local, TRIAGE_SLA_CRITICO=1 y TRIAGE_SLA_INTERVALO_EVALUACION=PT5S en .env, `docker compose up -d`, y repetir la demo.'
else
  printf 'X-API-Key: %s\n' "$TRIAGE_API_KEY" > "$TMP/cab-sse"
  curl -s -N "${CURL_EXTRA[@]}" -H "@$TMP/cab-sse" "$TRIAGE_URL/triage/alertas/stream" > "$TMP/sse" 2>/dev/null &
  SSE_PID=$!
  sleep 2
  IFS='|' read -r id fc sis dia spo2 temp dolor gcs queja esperado sla <<< "${EJEMPLOS[0]}"
  req POST "$TRIAGE_URL/triage/pacientes" "$TRIAGE_API_KEY" "$(cuerpo_paciente "$id" "$fc" "$sis" "$dia" "$spo2" "$temp" "$dolor" "$gcs" "$queja" 'Paciente de escalamiento (demo)')"
  ID_ESC="$(json_get "$CUERPO" id)"; SLA_ESC="$(json_get "$CUERPO" slaMinutos)"; ESPERA=$((SLA_ESC * 60 + 45))
  dato "paciente CRITICO registrado (id $ID_ESC, SLA $SLA_ESC min). Escuchando /triage/alertas/stream hasta $ESPERA s..."
  if esperar_hasta "$ESPERA" grep -q "$ID_ESC" "$TMP/sse"; then
    LINEA="$(grep '^data:' "$TMP/sse" | grep "$ID_ESC" | head -n1)"
    pasa "alerta SSE recibida: nivel $(json_get "$LINEA" nivel), espera $(json_get "$LINEA" minutosEspera) min de un SLA de $(json_get "$LINEA" slaMinutos) min"
    dato "    $LINEA"
    if printf '%s' "$LINEA" | grep -qi nombre; then falla "la alerta incluye el nombre"; else pasa "la alerta no lleva el nombre del paciente (dato sensible)"; fi
    req GET "$TRIAGE_URL/triage/pacientes/$ID_ESC" "$TRIAGE_API_KEY"
    if [ "$(json_get "$CUERPO" escalado)" = true ]; then pasa "GET del paciente: escalado = true (estado sigue en $(json_get "$CUERPO" estado))"; else falla "el paciente no figura como escalado"; fi
  else falla "no llego ninguna alerta para $ID_ESC en $ESPERA s (revisa TRIAGE_SLA_* y TRIAGE_SLA_INTERVALO_EVALUACION)"; fi
  kill "$SSE_PID" 2>/dev/null; SSE_PID=''
fi

# ============================================================================================================ 7
titulo 7 "Reporte de operacion del rango de la demo"
explica "GET /triage/reportes/operacion agrupa por hora (SQL con ventanas y percentiles): registrados, por nivel, espera promedio y p95. Rango [desde, hasta)."
DESDE="$(fecha_utc '-2 minutes' -2M)"; HASTA="$(fecha_utc '+2 minutes' +2M)"
req GET "$TRIAGE_URL/triage/reportes/operacion?desde=$DESDE&hasta=$HASTA" "$TRIAGE_API_KEY"
if [ "$CODIGO" = 200 ]; then
  TOTAL="$(printf '%s' "$CUERPO" | grep -o '"totalRegistrados":[0-9]*' | cut -d: -f2 | awk '{s+=$1} END{print s+0}')"
  # una linea por hora con pacientes (el JSON es plano en una linea: se parte por objeto de hora)
  printf '%s' "$CUERPO" | sed 's/{"hora"/\n{"hora"/g' | grep '"totalRegistrados":[1-9]' | head -n3 | while read -r h; do
    dato "hora $(json_get "$h" hora): registrados $(json_get "$h" totalRegistrados), atendidos $(json_get "$h" pacientesAtendidos), espera promedio $(json_get "$h" esperaPromedioMinutos) min, p95 $(json_get "$h" esperaP95Minutos) min"
  done
  if [ "$TOTAL" -ge "$REGISTRADOS" ]; then pasa "el reporte cuenta $TOTAL pacientes registrados en el rango (>= los $REGISTRADOS de esta demo)"; else falla "el reporte cuenta $TOTAL, se esperaban al menos $REGISTRADOS"; fi
else falla "HTTP $CODIGO $CUERPO"; fi

# ============================================================================================================ 8
titulo 8 "Asistente clinico: las 3 consultas del enunciado"
explica "RAG sobre los protocolos con cita obligatoria (RN-05). Dos preguntas tienen protocolo; la tercera no: debe decirlo sin inventar contenido clinico."
PREGUNTAS=('Cuales son las medidas iniciales ante una crisis hipertensiva?|PROTO-001' 'Cuando se administra el primer antibiotico en sepsis?|PROTO-002' 'Cual es el tratamiento de primera linea para la diabetes tipo 2?|')
for q in "${PREGUNTAS[@]}"; do
  pregunta="${q%%|*}"; protocolo="${q#*|}"
  req POST "$ASISTENTE_URL/asistente/consulta" "$ASSISTANT_API_KEY" "{\"pregunta\":\"$pregunta\"}"
  dato "pregunta: $pregunta"
  if [ "$CODIGO" != 200 ]; then falla "HTTP $CODIGO $CUERPO"; continue; fi
  explica "respuesta: $(json_texto "$CUERPO" respuesta)"
  if [ -n "$protocolo" ]; then
    fuente="$(printf '%s' "$CUERPO" | grep -o '"protocolo":"[^"]*","seccion":"[^"]*"' | head -n1 | sed 's/"protocolo":"\([^"]*\)","seccion":"\([^"]*\)"/\1 \/ \2/')"
    case "$fuente" in "$protocolo"*) pasa "fuente citada: $fuente" ;; *) falla "se esperaba la fuente $protocolo; llego: ${fuente:-ninguna}" ;; esac
  else
    if printf '%s' "$CUERPO" | grep -q '"fuentes":\[\]' && printf '%s' "$CUERPO" | grep -q 'No existe un protocolo'; then pasa "sin protocolo: aviso explicito y fuentes vacias (no se inventa contenido)"
    else falla "deberia responder que no hay protocolo, con fuentes vacias"; fi
  fi
done

# ============================================================================================================ 9
titulo 9 "Resumen"
TOTAL_C=$((OK + FALLO))
if [ "$FALLO" -eq 0 ]; then col="$C_OK"; else col="$C_KO"; fi
printf '%s    %s de %s comprobaciones OK, %s fallo(s), %s paso(s) omitido(s)%s\n' "$col" "$OK" "$TOTAL_C" "$FALLO" "$OMITIDO" "$C_0"
[ "$FALLO" -eq 0 ]
