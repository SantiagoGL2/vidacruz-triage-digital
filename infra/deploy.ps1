<#
.SYNOPSIS
  Despliega VidaCruz Triage en Azure (ACR + AKS) por pasos. Idempotente: se puede repetir cualquier paso.

.DESCRIPTION
  Pasos (-Paso):
    cuota         valida la cuota de vCPU y la disponibilidad de la talla (solo lectura)
    plan          what-if de Bicep: muestra que se creara (solo lectura)
    infra         crea o actualiza grupo de recursos + ACR + AKS con Bicep   (COSTO)
    imagenes      docker build (linux/amd64) + push de los 2 servicios al ACR + az acr import de las imagenes de terceros
    credenciales  az aks get-credentials (kubeconfig local)
    secretos      crea/actualiza el Secret vidacruz-secrets desde variables de entorno / .env (nunca se versiona)
    aplicar       renderiza k8s/overlays/azure con Kustomize, lo aplica y espera a que todo este Ready
    verificar     muestra pods, servicios, URLs publicas y la salud de ambos servicios
    todo          cuota -> infra -> imagenes -> credenciales -> secretos -> aplicar -> verificar

  Secretos: LLM_API_KEY (y opcionalmente LLM_BASE_URL, LLM_CHAT_MODEL, LLM_EMBEDDING_MODEL) se leen del entorno o del .env de la
  raiz. POSTGRES_PASSWORD, REDIS_PASSWORD, TRIAGE_API_KEY y ASSISTANT_API_KEY se generan al azar la primera vez y se guardan en
  infra/.env.azure (ignorado por git); se reutilizan en cada ejecucion.

.EXAMPLE
  ./infra/deploy.ps1 -Paso cuota
  ./infra/deploy.ps1 -Paso todo
#>
[CmdletBinding()]
param(
    [ValidateSet('cuota', 'plan', 'infra', 'imagenes', 'credenciales', 'secretos', 'aplicar', 'verificar', 'todo')]
    [string]$Paso = 'todo',
    [string]$Region = 'eastus',
    [string]$Prefijo = 'vidacruz',
    [string]$GrupoRecursos = 'rg-vidacruz-triage',
    [string]$TamanoNodo = 'Standard_D4s_v4',
    [string]$Namespace = 'vidacruz'
)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$nombreDespliegue = "$Prefijo-infra"
$archivoSecretos = Join-Path $PSScriptRoot '.env.azure'

# ---- az en el PATH aunque la sesion se haya abierto antes de instalarlo ----
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    $ruta = 'C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin'
    if (Test-Path $ruta) { $env:Path += ";$ruta" }
}

function Escribir($mensaje) { Write-Host "==> $mensaje" -ForegroundColor Cyan }

function Ejecutar {
    # Ejecuta un comando nativo y falla si devuelve codigo != 0.
    & $args[0] $args[1..($args.Length - 1)]
    if ($LASTEXITCODE -ne 0) { throw "Fallo: $($args -join ' ') (codigo $LASTEXITCODE)" }
}

function Leer-Env($ruta) {
    $valores = @{}
    if (Test-Path $ruta) {
        foreach ($linea in Get-Content $ruta) {
            if ($linea -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $linea.TrimStart().StartsWith('#')) {
                $valores[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
            }
        }
    }
    return $valores
}

function Nuevo-Secreto([int]$bytes = 24) {
    $b = New-Object byte[] $bytes
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    return (-join ($b | ForEach-Object { $_.ToString('x2') }))
}

function Obtener-Salidas {
    $json = az deployment sub show -n $nombreDespliegue --query properties.outputs -o json 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $json) { throw "No hay despliegue '$nombreDespliegue'. Ejecuta primero -Paso infra." }
    $o = $json | ConvertFrom-Json
    $sufijo = $o.acrNombre.value.Substring($o.acrNombre.value.Length - 6)
    return [pscustomobject]@{
        Acr          = $o.acrNombre.value
        AcrServer    = $o.acrLoginServer.value
        Aks          = $o.aksNombre.value
        Grupo        = $o.grupo.value
        EtiquetaTri  = "$Prefijo-triage-$sufijo"
        EtiquetaAsis = "$Prefijo-asistente-$sufijo"
    }
}

function Etiqueta-Imagen {
    $hash = (git -C $raiz rev-parse --short HEAD).Trim()
    if (git -C $raiz status --porcelain) { $hash += '-wip' + (Get-Date -Format 'yyyyMMddHHmm') }
    return $hash
}

# ---------------------------------------------------------------------------------------------------------------------
function Paso-Cuota {
    Escribir "Validando cuota en $Region para $TamanoNodo (solo lectura)"
    $cuenta = az account show -o json | ConvertFrom-Json
    Write-Host ("Suscripcion: {0} ({1}) - usuario {2}" -f $cuenta.name, $cuenta.state, $cuenta.user.name)

    # Con el cluster ya creado, su nodo ocupa toda la cuota: validarla de nuevo daria un falso "insuficiente".
    $previo = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    az aks show -g $GrupoRecursos -n "aks-$Prefijo-triage" --query name -o tsv 2>$null | Out-Null
    $existeAks = ($LASTEXITCODE -eq 0)
    $ErrorActionPreference = $previo
    if ($existeAks) { Write-Host "AKS ya existe en ${GrupoRecursos}: se omite la validacion de cuota (el nodo actual ya la ocupa)." -ForegroundColor Yellow; return }

    # "az vm list-sizes" responde en segundos (list-skus tarda minutos y no filtra en el servidor). Esta deprecado, pero
    # solo se usa para confirmar que la talla existe en la region y cuantas vCPU tiene.
    $tallas = az vm list-sizes -l $Region --query "[?name=='$TamanoNodo']" -o json --only-show-errors 2>$null | ConvertFrom-Json
    if (-not $tallas) { throw "La talla $TamanoNodo no existe en $Region." }
    $vcpus = [int]$tallas[0].numberOfCores
    # Familia de cuota (az vm list-usage) segun la talla. Solo las que se han usado; para otra, agregarla aqui.
    $familias = @{ 'Standard_D4s_v4' = 'standardDSv4Family'; 'Standard_D4as_v7' = 'StandardDasv7Family'; 'Standard_B4ms' = 'standardBSFamily' }
    $familia = $familias[$TamanoNodo]
    if (-not $familia) { throw "Agrega la familia de $TamanoNodo a la tabla de Paso-Cuota (az vm list-usage -l $Region)." }

    $usos = az vm list-usage -l $Region -o json | ConvertFrom-Json
    $total = $usos | Where-Object { $_.name.value -eq 'cores' }
    $fam = $usos | Where-Object { $_.name.value -eq $familia }
    $libreTotal = [int]$total.limit - [int]$total.currentValue
    $libreFam = if ($fam) { [int]$fam.limit - [int]$fam.currentValue } else { 0 }
    Write-Host ("Talla {0}: {1} vCPU, familia {2}" -f $TamanoNodo, $vcpus, $familia)
    Write-Host ("vCPU regionales: usadas {0} / limite {1} (libres {2})" -f $total.currentValue, $total.limit, $libreTotal)
    Write-Host ("vCPU familia   : usadas {0} / limite {1} (libres {2})" -f $fam.currentValue, $fam.limit, $libreFam)
    if ($libreTotal -lt $vcpus -or $libreFam -lt $vcpus) {
        throw "Cuota insuficiente: se necesitan $vcpus vCPU libres (regional y de la familia $familia)."
    }
    Write-Host "OK: la cuota alcanza para un nodo $TamanoNodo (sin margen para un segundo nodo)." -ForegroundColor Green

    foreach ($p in 'Microsoft.ContainerService', 'Microsoft.ContainerRegistry', 'Microsoft.Network', 'Microsoft.Compute', 'Microsoft.Storage') {
        $estado = az provider show -n $p --query registrationState -o tsv
        if ($estado -ne 'Registered') { throw "Proveedor $p sin registrar ($estado). Ejecuta: az provider register -n $p --wait" }
    }
    Write-Host "OK: proveedores registrados." -ForegroundColor Green
}

function Parametros-Bicep {
    return @('-l', $Region, '-n', $nombreDespliegue, '-f', (Join-Path $PSScriptRoot 'main.bicep'),
        '-p', "location=$Region", "prefijo=$Prefijo", "nombreGrupo=$GrupoRecursos", "tamanoNodo=$TamanoNodo")
}

function Paso-Plan {
    Escribir 'what-if de Bicep (no crea nada)'
    $p = Parametros-Bicep
    # Se resume (tipo y nombre): el JSON completo trae el ID de la suscripcion y no debe salir por pantalla.
    $json = az deployment sub what-if @p --no-pretty-print --only-show-errors
    if ($LASTEXITCODE -ne 0) { throw 'what-if fallo' }
    $cambios = ($json | ConvertFrom-Json).changes
    foreach ($c in $cambios) {
        $tipo = if ($c.after) { $c.after.type } else { $c.before.type }
        $nombre = if ($c.after) { $c.after.name } else { $c.before.name }
        if ($tipo -eq 'Microsoft.Authorization/roleAssignments') { $nombre = '(AcrPull para el kubelet de AKS)' }
        Write-Host ("  {0,-8} {1,-52} {2}" -f $c.changeType, $tipo, $nombre)
    }
    Write-Host "Total: $($cambios.Count) recurso(s)."
}

function Paso-Infra {
    Escribir "Creando/actualizando infraestructura en $Region (grupo $GrupoRecursos) - ESTO GENERA COSTO"
    $p = Parametros-Bicep
    Ejecutar az deployment sub create @p --query properties.outputs -o json --only-show-errors
    $s = Obtener-Salidas
    Write-Host ("ACR: {0} | AKS: {1} | Grupo: {2}" -f $s.AcrServer, $s.Aks, $s.Grupo) -ForegroundColor Green
}

function Paso-Imagenes {
    $s = Obtener-Salidas
    $tag = Etiqueta-Imagen
    # ACR Tasks (az acr build) esta bloqueado en suscripciones de prueba (TasksOperationsNotAllowed): las imagenes se
    # construyen con Docker local y se suben al ACR. En produccion las construiria el pipeline de CI (ADR 0008).
    Escribir 'Comprobando que Docker responde'
    docker version --format 'cliente {{.Client.Version}} / servidor {{.Server.Version}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker no responde: abre Docker Desktop (si esta en pausa, reanudalo) y reintenta.' }
    Escribir "Login en el ACR $($s.Acr)"
    Ejecutar az acr login -n $s.Acr --only-show-errors
    Escribir "Construyendo (linux/amd64) y subiendo imagenes, tag $tag"
    foreach ($servicio in 'triage-service', 'clinical-assistant-service') {
        $imagen = "$($s.AcrServer)/${servicio}:$tag"
        Ejecutar docker build --platform linux/amd64 -t $imagen (Join-Path $raiz $servicio)
        Ejecutar docker push $imagen
    }
    Set-Content -Path (Join-Path $PSScriptRoot '.ultimo-tag') -Value $tag -Encoding ascii

    Escribir 'Copiando imagenes de terceros al ACR (az acr import; evita limites de Docker Hub)'
    $terceros = @(
        @{ origen = 'docker.io/library/postgres:16-alpine'; destino = 'postgres:16-alpine' },
        @{ origen = 'docker.io/apache/kafka:3.9.1'; destino = 'kafka:3.9.1' },
        @{ origen = 'docker.io/redis/redis-stack-server:7.4.0-v3'; destino = 'redis-stack-server:7.4.0-v3' },
        @{ origen = 'docker.io/library/busybox:1.37'; destino = 'busybox:1.37' }
    )
    foreach ($img in $terceros) {
        $repo, $etiqueta = $img.destino.Split(':')
        # Si el repositorio aun no existe, az escribe en stderr: no debe tratarse como error (ErrorActionPreference=Stop).
        $previo = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $existe = az acr repository show-tags -n $s.Acr --repository $repo -o tsv 2>$null
        $ErrorActionPreference = $previo
        if ($existe -and ($existe -contains $etiqueta)) { Write-Host "  ya existe $($img.destino)"; continue }
        Ejecutar az acr import -n $s.Acr --source $img.origen -t $img.destino --only-show-errors
    }
}

function Paso-Credenciales {
    $s = Obtener-Salidas
    Escribir 'Obteniendo credenciales de AKS (kubeconfig local)'
    Ejecutar az aks get-credentials -g $s.Grupo -n $s.Aks --overwrite-existing --only-show-errors
    Ejecutar kubectl get nodes -o wide
}

function Paso-Secretos {
    Escribir "Creando el Secret vidacruz-secrets en el namespace $Namespace (los valores no se imprimen)"
    $entorno = Leer-Env (Join-Path $raiz '.env')
    $propios = Leer-Env $archivoSecretos
    foreach ($k in 'POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'TRIAGE_API_KEY', 'ASSISTANT_API_KEY') {
        if (-not $propios.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($propios[$k])) {
            $propios[$k] = Nuevo-Secreto
            Write-Host "  generado $k (guardado en infra/.env.azure)"
        }
    }
    $lineas = $propios.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }
    [IO.File]::WriteAllText($archivoSecretos, (($lineas -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))

    $llm = if ($env:LLM_API_KEY) { $env:LLM_API_KEY } else { $entorno['LLM_API_KEY'] }
    if ([string]::IsNullOrWhiteSpace($llm) -or $llm -like 'change-me*') {
        throw 'Falta LLM_API_KEY (variable de entorno o .env de la raiz) con una clave real.'
    }

    $temporal = New-TemporaryFile
    try {
        $contenido = @(
            "POSTGRES_PASSWORD=$($propios['POSTGRES_PASSWORD'])",
            "REDIS_PASSWORD=$($propios['REDIS_PASSWORD'])",
            "TRIAGE_API_KEY=$($propios['TRIAGE_API_KEY'])",
            "ASSISTANT_API_KEY=$($propios['ASSISTANT_API_KEY'])",
            "LLM_API_KEY=$llm"
        ) -join "`n"
        [IO.File]::WriteAllText($temporal.FullName, $contenido + "`n", (New-Object Text.UTF8Encoding($false)))
        kubectl create namespace $Namespace --dry-run=client -o yaml | kubectl apply -f - | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el namespace' }
        kubectl -n $Namespace create secret generic vidacruz-secrets --from-env-file=$($temporal.FullName) --dry-run=client -o yaml | kubectl apply -f - | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el Secret' }
    }
    finally {
        Remove-Item $temporal.FullName -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'OK: Secret vidacruz-secrets aplicado.' -ForegroundColor Green
}

function Paso-Aplicar {
    $s = Obtener-Salidas
    $tagArchivo = Join-Path $PSScriptRoot '.ultimo-tag'
    if (-not (Test-Path $tagArchivo)) { throw 'No hay imagenes construidas. Ejecuta primero -Paso imagenes.' }
    $tag = (Get-Content $tagArchivo -Raw).Trim()

    Escribir "Aplicando k8s/overlays/azure (imagenes $tag)"
    $render = kubectl kustomize (Join-Path $raiz 'k8s/overlays/azure')
    if ($LASTEXITCODE -ne 0) { throw 'kubectl kustomize fallo' }
    $render = ($render -join "`n") `
        -replace 'ACR_LOGIN_SERVER', $s.AcrServer `
        -replace 'IMAGE_TAG', $tag `
        -replace 'DNS_LABEL_TRIAGE', $s.EtiquetaTri `
        -replace 'DNS_LABEL_ASISTENTE', $s.EtiquetaAsis

    # Un Job es inmutable: se recrea en cada despliegue (crear topics es idempotente).
    kubectl -n $Namespace delete job kafka-init --ignore-not-found | Out-Null
    $render | kubectl apply -f -
    if ($LASTEXITCODE -ne 0) { throw 'kubectl apply fallo' }

    Escribir 'Esperando a que todo este listo'
    foreach ($ss in 'postgres', 'kafka', 'redis') { Ejecutar kubectl -n $Namespace rollout status statefulset/$ss --timeout=300s }
    Ejecutar kubectl -n $Namespace wait --for=condition=complete job/kafka-init --timeout=240s
    foreach ($d in 'triage-service', 'clinical-assistant-service') { Ejecutar kubectl -n $Namespace rollout status deployment/$d --timeout=420s }
}

function Esperar-IpPublica($servicio) {
    for ($i = 0; $i -lt 60; $i++) {
        $ip = kubectl -n $Namespace get svc $servicio -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>$null
        if ($ip) { return $ip }
        Start-Sleep -Seconds 5
    }
    throw "El Service $servicio no obtuvo IP publica a tiempo."
}

function Paso-Verificar {
    $s = Obtener-Salidas
    Escribir "kubectl get pods,svc -n $Namespace"
    Ejecutar kubectl get 'pods,svc' -n $Namespace -o wide
    $ipT = Esperar-IpPublica 'triage-service'
    $ipA = Esperar-IpPublica 'clinical-assistant-service'
    $urlT = "http://$($s.EtiquetaTri).$Region.cloudapp.azure.com"
    $urlA = "http://$($s.EtiquetaAsis).$Region.cloudapp.azure.com"
    Write-Host ''
    Write-Host "triage-service             : $urlT   (IP $ipT)" -ForegroundColor Green
    Write-Host "clinical-assistant-service : $urlA   (IP $ipA)" -ForegroundColor Green
    Write-Host "Swagger UI                 : $urlT/swagger-ui.html  |  $urlA/swagger-ui.html"
    foreach ($u in $urlT, $urlA) {
        try { $r = Invoke-RestMethod "$u/actuator/health/readiness" -TimeoutSec 15; Write-Host "  $u -> $($r.status)" }
        catch { Write-Host "  $u -> sin respuesta aun ($($_.Exception.Message))" -ForegroundColor Yellow }
    }
    Write-Host 'Las API keys estan en infra/.env.azure (TRIAGE_API_KEY, ASSISTANT_API_KEY).'
}

switch ($Paso) {
    'cuota' { Paso-Cuota }
    'plan' { Paso-Plan }
    'infra' { Paso-Infra }
    'imagenes' { Paso-Imagenes }
    'credenciales' { Paso-Credenciales }
    'secretos' { Paso-Secretos }
    'aplicar' { Paso-Aplicar }
    'verificar' { Paso-Verificar }
    'todo' { Paso-Cuota; Paso-Infra; Paso-Imagenes; Paso-Credenciales; Paso-Secretos; Paso-Aplicar; Paso-Verificar }
}
