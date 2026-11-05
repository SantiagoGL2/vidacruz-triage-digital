<#
.SYNOPSIS
  Demo de punta a punta de VidaCruz Triage contra local o contra Azure (HTTPS). Salida coloreada, un paso explicado a la vez.

.DESCRIPTION
  Recorrido: 1 salud · 2 registrar E1-E6 y comparar con el nivel esperado · 3 validacion (400) · 4 transiciones (200 y 409) ·
  5 estado de urgencias antes/despues de un alta (consistencia eventual) · 6 escalamiento por SLA con alerta SSE (solo si el SLA
  es corto) · 7 reporte de operacion · 8 las 3 consultas de ejemplo al asistente · 9 resumen.

  Las URLs y las API keys NUNCA van en el codigo: se dan por parametros, por variables de entorno
  (TRIAGE_URL, ASISTENTE_URL, TRIAGE_API_KEY, ASSISTANT_API_KEY) o con -ArchivoEnv (archivo KEY=VALUE, p. ej. .env o infra/.env.azure).
  Orden de prioridad: parametro > variable de entorno > archivo > valor por defecto (solo para las URLs: localhost).

.PARAMETER Escalamiento
  auto (defecto): ejecuta el paso 6 solo si el SLA de CRITICO cabe en -EsperaMaximaEscalamiento. si: lo fuerza. no: lo omite.

.EXAMPLE
  # Local (docker compose up --build; para ver el escalamiento: TRIAGE_SLA_CRITICO=1 y TRIAGE_SLA_INTERVALO_EVALUACION=PT5S en .env)
  ./scripts/demo.ps1 -ArchivoEnv .env

.EXAMPLE
  # Azure por HTTPS (las claves estan en infra/.env.azure; las URLs son las del despliegue)
  ./scripts/demo.ps1 -ArchivoEnv infra/.env.azure -UrlTriage https://<triage>.eastus.cloudapp.azure.com -UrlAsistente https://<asistente>.eastus.cloudapp.azure.com
#>
[CmdletBinding()]
param(
    [string]$UrlTriage,
    [string]$UrlAsistente,
    [string]$ApiKeyTriage,
    [string]$ApiKeyAsistente,
    [string]$ArchivoEnv,
    [ValidateSet('auto', 'si', 'no')]
    [string]$Escalamiento = 'auto',
    [int]$EsperaMaximaEscalamiento = 150,
    [switch]$NoVerificarCertificado
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if ($NoVerificarCertificado) { [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true } }

# ---- configuracion: parametro > entorno > archivo > defecto ----
$desdeArchivo = @{}
if ($ArchivoEnv) {
    if (-not (Test-Path $ArchivoEnv)) { throw "No existe el archivo $ArchivoEnv" }
    foreach ($l in Get-Content $ArchivoEnv) {
        if ($l -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $l.TrimStart().StartsWith('#')) { $desdeArchivo[$Matches[1]] = $Matches[2].Trim('"') }
    }
}
function Elegir($parametro, $variable, $defecto) {
    if ($parametro) { return $parametro }
    $e = [Environment]::GetEnvironmentVariable($variable)
    if ($e) { return $e }
    if ($desdeArchivo.ContainsKey($variable) -and $desdeArchivo[$variable]) { return $desdeArchivo[$variable] }
    return $defecto
}
$UrlTriage = (Elegir $UrlTriage 'TRIAGE_URL' 'http://localhost:8081').TrimEnd('/')
$UrlAsistente = (Elegir $UrlAsistente 'ASISTENTE_URL' 'http://localhost:8082').TrimEnd('/')
$ApiKeyTriage = Elegir $ApiKeyTriage 'TRIAGE_API_KEY' $null
$ApiKeyAsistente = Elegir $ApiKeyAsistente 'ASSISTANT_API_KEY' $null
if (-not $ApiKeyTriage -or -not $ApiKeyAsistente) {
    throw 'Faltan las API keys: usa -ApiKeyTriage/-ApiKeyAsistente, las variables TRIAGE_API_KEY/ASSISTANT_API_KEY o -ArchivoEnv.'
}

# ---- salida ----
$script:ok = 0; $script:fallo = 0; $script:omitido = 0
function Titulo($n, $texto) { Write-Host ''; Write-Host ("[{0}] {1}" -f $n, $texto) -ForegroundColor Cyan }
function Explica($texto) { Write-Host "    $texto" -ForegroundColor DarkGray }
function Dato($texto) { Write-Host "    $texto" }
function Pasa($texto) { $script:ok++; Write-Host "    OK     $texto" -ForegroundColor Green }
function Falla($texto) { $script:fallo++; Write-Host "    FALLO  $texto" -ForegroundColor Red }
function Omite($texto) { $script:omitido++; Write-Host "    OMITIDO  $texto" -ForegroundColor Yellow }
function Comprueba($condicion, $textoOk, $textoFallo) { if ($condicion) { Pasa $textoOk } else { Falla $textoFallo } }

function Invocar($metodo, $url, $clave, $cuerpo) {
    $p = @{ Method = $metodo; Uri = $url; UseBasicParsing = $true; TimeoutSec = 60; Headers = @{} }
    if ($clave) { $p.Headers['X-API-Key'] = $clave }
    if ($cuerpo) { $p.Body = [Text.Encoding]::UTF8.GetBytes($cuerpo); $p.ContentType = 'application/json; charset=utf-8' }
    $cab = $null
    try {
        $r = Invoke-WebRequest @p -ErrorAction Stop
        $codigo = [int]$r.StatusCode; $texto = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray()); $cab = $r.Headers
    }
    catch {
        $resp = $_.Exception.Response
        if (-not $resp) { throw "No se pudo conectar con $url : $($_.Exception.Message)" }
        $codigo = [int]$resp.StatusCode
        # PowerShell 5.1 ya consumio el stream de la respuesta de error: el cuerpo viene en ErrorDetails.
        $texto = $_.ErrorDetails.Message
        if (-not $texto) { try { $texto = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd() } catch { $texto = '' } }
        $cab = $resp.Headers
    }
    $json = $null
    if ($texto -and $texto.TrimStart().StartsWith('{')) { try { $json = $texto | ConvertFrom-Json } catch { $json = $null } }
    return [pscustomobject]@{ Codigo = $codigo; Texto = $texto; Json = $json; Cab = $cab }
}

function Esperar-Hasta([scriptblock]$condicion, [int]$segundos, [int]$cadaMs = 700) {
    $limite = (Get-Date).AddSeconds($segundos)
    while ((Get-Date) -lt $limite) { if (& $condicion) { return $true }; Start-Sleep -Milliseconds $cadaMs }
    return $false
}

function Estado-Urgencias { Invocar 'GET' "$UrlAsistente/asistente/estado-urgencias" $ApiKeyAsistente $null }

# ---- datos: E1-E6 del enunciado (sin tildes: el curl de Windows no siempre envia UTF-8) ----
$ejemplos = @(
    @{ id = 'E1'; fc = 130; sis = 180; dia = 110; spo2 = 97; temp = 36.8; dolor = 8; gcs = 15; queja = 'Dolor toracico'; esperado = 'CRITICO'; sla = 5 },
    @{ id = 'E2'; fc = 95; sis = 150; dia = 95; spo2 = 91; temp = 37.0; dolor = 4; gcs = 15; queja = 'Dificultad para respirar'; esperado = 'URGENTE'; sla = 10 },
    @{ id = 'E3'; fc = 88; sis = 130; dia = 85; spo2 = 98; temp = 38.2; dolor = 6; gcs = 15; queja = 'Fiebre'; esperado = 'URGENTE_2'; sla = 30 },
    @{ id = 'E4'; fc = 72; sis = 120; dia = 80; spo2 = 99; temp = 36.5; dolor = 2; gcs = 15; queja = 'Herida por cortar'; esperado = 'NO_URGENTE'; sla = 60 },
    @{ id = 'E5'; fc = 70; sis = 115; dia = 75; spo2 = 99; temp = 36.4; dolor = 0; gcs = 15; queja = 'Renovacion de recetario'; esperado = 'NO_URGENTE_2'; sla = 120 },
    @{ id = 'E6'; fc = 35; sis = 80; dia = 50; spo2 = 85; temp = 35.9; dolor = 0; gcs = 6; queja = 'Inconsciente'; esperado = 'CRITICO'; sla = 5 }
)
function Cuerpo-Paciente($e, $nombre) {
    return (@{ nombreCompleto = $nombre; edad = 45; frecuenciaCardiaca = $e.fc; presionSistolica = $e.sis; presionDiastolica = $e.dia
            spo2 = $e.spo2; temperatura = $e.temp; dolor = $e.dolor; gcs = $e.gcs; quejaPrincipal = $e.queja } | ConvertTo-Json -Compress)
}

$inicio = (Get-Date).ToUniversalTime()
Write-Host 'VidaCruz Triage - demo de punta a punta' -ForegroundColor White
Dato "triage     : $UrlTriage"
Dato "asistente  : $UrlAsistente"
Dato '(las API keys se toman de parametros, entorno o archivo; no se muestran)'

# ======================================================================================================== 1
Titulo 1 'Salud de ambos servicios'
Explica 'GET /actuator/health/readiness es publico: dice si el servicio puede recibir trafico (triage: base de datos; asistente: Redis e indice de protocolos).'
foreach ($par in @(@('triage-service', $UrlTriage), @('clinical-assistant-service', $UrlAsistente))) {
    $r = Invocar 'GET' "$($par[1])/actuator/health/readiness" $null $null
    $estado = if ($r.Json) { $r.Json.status } else { '?' }
    Comprueba ($r.Codigo -eq 200 -and $estado -eq 'UP') "$($par[0]) readiness = UP" "$($par[0]) readiness = $estado (HTTP $($r.Codigo)). Si es el asistente: revisa LLM_API_KEY (modo degradado) o espera a que termine de indexar."
}
$base = Estado-Urgencias
$activosAntes = if ($base.Json) { [int]$base.Json.totalActivos } else { 0 }
Dato "pacientes activos en la vista de urgencias antes de empezar: $activosAntes"

# ======================================================================================================== 2
Titulo 2 'Registrar los pacientes de ejemplo E1-E6 y comparar el nivel con el esperado'
Explica 'POST /triage/pacientes clasifica con reglas objetivas (RN-01) y fija el SLA (RN-02). Se compara con la columna "Nivel esperado" del enunciado.'
$registrados = @{}
foreach ($e in $ejemplos) {
    $r = Invocar 'POST' "$UrlTriage/triage/pacientes" $ApiKeyTriage (Cuerpo-Paciente $e "Paciente $($e.id) (demo)")
    if ($r.Codigo -ne 201) { Falla "$($e.id): HTTP $($r.Codigo) $($r.Texto)"; continue }
    $registrados[$e.id] = $r.Json.id
    $notaSla = if ([int]$r.Json.slaMinutos -ne $e.sla) { " (SLA distinto del enunciado = $($e.sla): configuracion)" } else { '' }
    Comprueba ($r.Json.nivel -eq $e.esperado) ("{0}: {1,-12} SLA {2,3} min  (esperado {3}){4}" -f $e.id, $r.Json.nivel, $r.Json.slaMinutos, $e.esperado, $notaSla) `
        ("{0}: nivel {1}, se esperaba {2}" -f $e.id, $r.Json.nivel, $e.esperado)
}
$slaCritico = if ($registrados.ContainsKey('E1')) { [int](Invocar 'GET' "$UrlTriage/triage/pacientes/$($registrados['E1'])" $ApiKeyTriage $null).Json.slaMinutos } else { 5 }

# ======================================================================================================== 3
Titulo 3 'Validacion: datos fuera de rango'
Explica 'Un paciente con valores imposibles recibe un unico 400 que lista TODOS los campos invalidos (nunca se guarda).'
$malo = '{"nombreCompleto":"Ab","edad":150,"frecuenciaCardiaca":300,"presionSistolica":120,"presionDiastolica":130,"spo2":120,"temperatura":36.5,"dolor":11,"gcs":15,"quejaPrincipal":"Prueba"}'
$r = Invocar 'POST' "$UrlTriage/triage/pacientes" $ApiKeyTriage $malo
$campos = @(); if ($r.Json -and $r.Json.errores) { $campos = @($r.Json.errores | ForEach-Object { $_.campo }) }
Comprueba ($r.Codigo -eq 400 -and $campos.Count -ge 5) ("HTTP 400 con {0} campos invalidos: {1}" -f $campos.Count, ($campos -join ', ')) ("se esperaba 400 con varios campos; HTTP $($r.Codigo) $($r.Texto)")
if ($r.Json -and $r.Json.errores) { $r.Json.errores | Select-Object -First 3 | ForEach-Object { Explica ("{0} = {1} -> {2}" -f $_.campo, $_.valor, $_.mensaje) } }

# ======================================================================================================== 4
Titulo 4 'Transiciones de estado: una valida y una invalida'
Explica 'REGISTRADO -> EN_CONSULTA es valida (200). Volver a REGISTRADO no lo es (409 con mensaje descriptivo). Cada cambio queda auditado.'
$idE2 = $registrados['E2']
if ($idE2) {
    $r = Invocar 'PATCH' "$UrlTriage/triage/pacientes/$idE2/estado" $ApiKeyTriage '{"estado":"EN_CONSULTA"}'
    Comprueba ($r.Codigo -eq 200 -and $r.Json.estado -eq 'EN_CONSULTA') "E2: REGISTRADO -> EN_CONSULTA = HTTP 200" "E2: HTTP $($r.Codigo) $($r.Texto)"
    $r = Invocar 'PATCH' "$UrlTriage/triage/pacientes/$idE2/estado" $ApiKeyTriage '{"estado":"REGISTRADO"}'
    Comprueba ($r.Codigo -eq 409) ("E2: EN_CONSULTA -> REGISTRADO = HTTP 409: " + $(if ($r.Json) { $r.Json.detail })) "se esperaba 409; HTTP $($r.Codigo) $($r.Texto)"
}
else { Falla 'sin E2 registrado' }

# ======================================================================================================== 5
Titulo 5 'Estado de urgencias en el asistente, antes y despues de un alta'
Explica 'El asistente NO llama a triage: reconstruye este estado consumiendo los eventos de Kafka (consistencia eventual), por eso se espera unos segundos.'
$esperadoAntes = $activosAntes + $registrados.Count
$llego = Esperar-Hasta { (Estado-Urgencias).Json.totalActivos -eq $esperadoAntes } 25
$antes = (Estado-Urgencias).Json
Comprueba $llego ("ANTES : activos = {0}  porNivel = {1}" -f $antes.totalActivos, ($antes.porNivel | ConvertTo-Json -Compress)) ("la vista no llego a $esperadoAntes activos (tiene $($antes.totalActivos))")
$idE4 = $registrados['E4']
if ($idE4) {
    $r = Invocar 'PATCH' "$UrlTriage/triage/pacientes/$idE4/estado" $ApiKeyTriage '{"estado":"ALTA"}'
    Dato "alta directa de E4 (NO_URGENTE): HTTP $($r.Codigo)"
    $objetivo = [int]$antes.totalActivos - 1
    $bajo = Esperar-Hasta { (Estado-Urgencias).Json.totalActivos -eq $objetivo } 25
    $despues = (Estado-Urgencias).Json
    Comprueba ($bajo -and ($despues.porNivel.NO_URGENTE -eq $antes.porNivel.NO_URGENTE - 1)) `
        ("DESPUES: activos = {0}  porNivel = {1}  (NO_URGENTE {2} -> {3})" -f $despues.totalActivos, ($despues.porNivel | ConvertTo-Json -Compress), $antes.porNivel.NO_URGENTE, $despues.porNivel.NO_URGENTE) `
        "la vista no refleja el alta (activos $($despues.totalActivos), esperados $objetivo)"
}
else { Falla 'sin E4 registrado' }

# ======================================================================================================== 6
Titulo 6 'Escalamiento por SLA y alerta en vivo (SSE)'
Explica 'Un paciente REGISTRADO que supera su SLA se escala solo (RF-03) y se avisa al equipo medico por SSE (RF-06). SLA real de CRITICO: 5 min.'
$ejecutar = switch ($Escalamiento) { 'si' { $true } 'no' { $false } default { ($slaCritico * 60) -le $EsperaMaximaEscalamiento } }
if (-not $ejecutar) {
    $motivo = if ($Escalamiento -eq 'no') { 'pedido con -Escalamiento no' } else { "el SLA de CRITICO es $slaCritico min (> $EsperaMaximaEscalamiento s de espera maxima); en este entorno el escalamiento tardaria demasiado" }
    Omite "paso omitido: $motivo."
    Explica 'Para verlo: en local, TRIAGE_SLA_CRITICO=1 y TRIAGE_SLA_INTERVALO_EVALUACION=PT5S en .env, `docker compose up -d`, y repetir la demo.'
}
else {
    $cabeceras = Join-Path $env:TEMP ("demo-sse-cab-" + [guid]::NewGuid().ToString('N') + '.txt')
    $salida = Join-Path $env:TEMP ("demo-sse-" + [guid]::NewGuid().ToString('N') + '.txt')
    [IO.File]::WriteAllText($cabeceras, "X-API-Key: $ApiKeyTriage`n")
    $curlArgs = @('-s', '-N', '-H', "@$cabeceras", '-o', $salida)
    if ($NoVerificarCertificado) { $curlArgs += '-k' }
    $curlArgs += "$UrlTriage/triage/alertas/stream"
    $sse = Start-Process -FilePath 'curl.exe' -ArgumentList $curlArgs -PassThru -WindowStyle Hidden
    try {
        Start-Sleep -Seconds 2
        $e1 = $ejemplos[0]
        $r = Invocar 'POST' "$UrlTriage/triage/pacientes" $ApiKeyTriage (Cuerpo-Paciente $e1 'Paciente de escalamiento (demo)')
        $idEsc = $r.Json.id
        $espera = ([int]$r.Json.slaMinutos * 60) + 45
        Dato "paciente CRITICO registrado (id $idEsc, SLA $($r.Json.slaMinutos) min). Escuchando /triage/alertas/stream hasta $espera s..."
        $recibida = Esperar-Hasta { (Test-Path $salida) -and ((Get-Content $salida -Raw -ErrorAction SilentlyContinue) -match [regex]::Escape($idEsc)) } $espera 1000
        if ($recibida) {
            $linea = (Get-Content $salida | Where-Object { $_ -like 'data:*' -and $_ -match [regex]::Escape($idEsc) } | Select-Object -First 1)
            $alerta = ($linea -replace '^data:\s*', '') | ConvertFrom-Json
            Pasa ("alerta SSE recibida: nivel {0}, espera {1} min de un SLA de {2} min" -f $alerta.nivel, $alerta.minutosEspera, $alerta.slaMinutos)
            Dato ("    " + $linea.Trim())
            Comprueba (-not ($linea -match 'nombre')) 'la alerta no lleva el nombre del paciente (dato sensible)' 'la alerta incluye el nombre'
            $p = (Invocar 'GET' "$UrlTriage/triage/pacientes/$idEsc" $ApiKeyTriage $null).Json
            Comprueba ($p.escalado -eq $true) "GET del paciente: escalado = true (estado sigue en $($p.estado))" 'el paciente no figura como escalado'
        }
        else { Falla "no llego ninguna alerta para $idEsc en $espera s (revisa TRIAGE_SLA_* y TRIAGE_SLA_INTERVALO_EVALUACION)" }
    }
    finally {
        if ($sse -and -not $sse.HasExited) { Stop-Process -Id $sse.Id -Force -ErrorAction SilentlyContinue }
        Remove-Item $cabeceras, $salida -Force -ErrorAction SilentlyContinue
    }
}

# ======================================================================================================== 7
Titulo 7 'Reporte de operacion del rango de la demo'
Explica 'GET /triage/reportes/operacion agrupa por hora (SQL con ventanas y percentiles): registrados, por nivel, espera promedio y p95. Rango [desde, hasta).'
$desde = $inicio.AddMinutes(-2).ToString("yyyy-MM-ddTHH:mm:ss") + '+00:00'
$hasta = (Get-Date).ToUniversalTime().AddMinutes(2).ToString("yyyy-MM-ddTHH:mm:ss") + '+00:00'
$r = Invocar 'GET' ("$UrlTriage/triage/reportes/operacion?desde=" + [uri]::EscapeDataString($desde) + '&hasta=' + [uri]::EscapeDataString($hasta)) $ApiKeyTriage $null
if ($r.Codigo -eq 200) {
    $conDatos = @($r.Json.horas | Where-Object { $_.totalRegistrados -gt 0 })
    $total = ($conDatos | Measure-Object -Property totalRegistrados -Sum).Sum
    foreach ($h in $conDatos) {
        Dato ("hora {0}: registrados {1}, atendidos {2}, espera promedio {3} min, p95 {4} min" -f $h.hora, $h.totalRegistrados, $h.pacientesAtendidos, $h.esperaPromedioMinutos, $h.esperaP95Minutos)
        Dato ("          por nivel: " + ($h.porNivel | ConvertTo-Json -Compress))
    }
    Comprueba ($total -ge $registrados.Count) "el reporte cuenta $total pacientes registrados en el rango (>= los $($registrados.Count) de esta demo)" "el reporte cuenta $total, se esperaban al menos $($registrados.Count)"
}
else { Falla "HTTP $($r.Codigo) $($r.Texto)" }

# ======================================================================================================== 8
Titulo 8 'Asistente clinico: las 3 consultas del enunciado'
Explica 'RAG sobre los protocolos con cita obligatoria (RN-05). Dos preguntas tienen protocolo; la tercera no: debe decirlo sin inventar contenido clinico.'
$preguntas = @(
    @{ p = 'Cuales son las medidas iniciales ante una crisis hipertensiva?'; protocolo = 'PROTO-001' },
    @{ p = 'Cuando se administra el primer antibiotico en sepsis?'; protocolo = 'PROTO-002' },
    @{ p = 'Cual es el tratamiento de primera linea para la diabetes tipo 2?'; protocolo = $null }
)
foreach ($q in $preguntas) {
    $r = Invocar 'POST' "$UrlAsistente/asistente/consulta" $ApiKeyAsistente (@{ pregunta = $q.p } | ConvertTo-Json -Compress)
    Dato "pregunta: $($q.p)"
    if ($r.Codigo -ne 200) { Falla "HTTP $($r.Codigo) $($r.Texto)"; continue }
    $fuentes = @($r.Json.fuentes)
    Explica ("respuesta: " + $r.Json.respuesta)
    if ($q.protocolo) {
        $citas = ($fuentes | ForEach-Object { "$($_.protocolo) / $($_.seccion)" }) -join '; '
        Comprueba ($fuentes.Count -ge 1 -and $fuentes[0].protocolo -eq $q.protocolo) "fuente citada: $citas" "se esperaba la fuente $($q.protocolo); llego: $citas"
    }
    else {
        Comprueba ($fuentes.Count -eq 0 -and $r.Json.respuesta -match 'No existe un protocolo') 'sin protocolo: aviso explicito y fuentes vacias (no se inventa contenido)' 'deberia responder que no hay protocolo, con fuentes vacias'
    }
}

# ======================================================================================================== 9
Titulo 9 'Resumen'
$total = $script:ok + $script:fallo
$color = if ($script:fallo -eq 0) { 'Green' } else { 'Red' }
Write-Host ("    {0} de {1} comprobaciones OK, {2} fallo(s), {3} paso(s) omitido(s)" -f $script:ok, $total, $script:fallo, $script:omitido) -ForegroundColor $color
if ($script:fallo -gt 0) { exit 1 }
