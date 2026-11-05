<#
.SYNOPSIS
  Destruye TODO lo de VidaCruz en Azure: borra el grupo de recursos (AKS, ACR, discos, IP publicas, balanceadores).

.DESCRIPTION
  Es irreversible: se pierden los datos de PostgreSQL, Kafka y Redis (viven en discos del grupo). Pide confirmacion escribiendo
  el nombre del grupo, salvo que se use -Forzar. Tambien quita el contexto de kubectl y los archivos locales de secretos.

.EXAMPLE
  ./infra/destroy.ps1
  ./infra/destroy.ps1 -Forzar -SinEsperar
#>
[CmdletBinding()]
param(
    [string]$GrupoRecursos = 'rg-vidacruz-triage',
    [string]$Prefijo = 'vidacruz',
    [switch]$Forzar,
    [switch]$SinEsperar,
    [switch]$ConservarSecretosLocales
)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    $ruta = 'C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin'
    if (Test-Path $ruta) { $env:Path += ";$ruta" }
}

$existe = az group exists -n $GrupoRecursos
if ($existe -ne 'true') { Write-Host "El grupo $GrupoRecursos no existe: nada que borrar." -ForegroundColor Yellow }
else {
    Write-Host "Se va a BORRAR el grupo de recursos '$GrupoRecursos' y todo su contenido:" -ForegroundColor Red
    az resource list -g $GrupoRecursos --query "[].{tipo:type, nombre:name}" -o table
    if (-not $Forzar) {
        $respuesta = Read-Host "Escribe el nombre del grupo para confirmar"
        if ($respuesta -ne $GrupoRecursos) { throw 'Confirmacion incorrecta: no se borro nada.' }
    }
    $opciones = @('group', 'delete', '-n', $GrupoRecursos, '--yes')
    if ($SinEsperar) { $opciones += '--no-wait' }
    az @opciones
    if ($LASTEXITCODE -ne 0) { throw 'az group delete fallo' }
    Write-Host ($(if ($SinEsperar) { 'Borrado en curso (se completa en unos minutos).' } else { 'Grupo borrado.' })) -ForegroundColor Green
}

# Limpieza local: contexto de kubectl del clúster y el registro del despliegue a nivel de suscripcion.
$aks = "aks-$Prefijo-triage"
kubectl config delete-context $aks 2>$null | Out-Null
kubectl config delete-cluster $aks 2>$null | Out-Null
kubectl config unset "users.clusterUser_${GrupoRecursos}_$aks" 2>$null | Out-Null
az deployment sub delete -n "$Prefijo-infra" 2>$null | Out-Null
if (-not $ConservarSecretosLocales) {
    Remove-Item (Join-Path $PSScriptRoot '.env.azure'), (Join-Path $PSScriptRoot '.ultimo-tag') -Force -ErrorAction SilentlyContinue
    Write-Host 'Eliminados infra/.env.azure y infra/.ultimo-tag (las claves se regeneran en el proximo despliegue).'
}
