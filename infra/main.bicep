// Infraestructura de VidaCruz Triage en Azure: grupo de recursos + ACR + AKS (ver docs/adr/0008-despliegue-en-aks.md).
// Despliegue a nivel de suscripcion porque crea el propio grupo de recursos:
//   az deployment sub create -l eastus -f infra/main.bicep
targetScope = 'subscription'

@description('Region de Azure.')
param location string = 'eastus'

@description('Prefijo de los nombres (minusculas y numeros, 3-12 caracteres). El ACR agrega un sufijo unico porque su nombre es global.')
@minLength(3)
@maxLength(12)
param prefijo string = 'vidacruz'

@description('Nombre del grupo de recursos (todo lo del proyecto vive aqui, asi se destruye de una vez).')
param nombreGrupo string = 'rg-${prefijo}-triage'

@description('Tamano del unico nodo de AKS: 4 vCPU / 16 GiB (cabe justo en la cuota de prueba). Standard_B4ms, la talla prevista, no esta permitida por AKS en la suscripcion de prueba (ver ADR 0008).')
param tamanoNodo string = 'Standard_D4s_v4'

@description('Version de Kubernetes. Vacio = la predeterminada de AKS en la region.')
param versionKubernetes string = ''

@description('Etiquetas de todos los recursos.')
param etiquetas object = {
  proyecto: 'vidacruz-triage'
  entorno: 'prueba'
}

resource grupo 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: nombreGrupo
  location: location
  tags: etiquetas
}

module recursos 'modules/recursos.bicep' = {
  name: 'recursos-${prefijo}'
  scope: grupo
  params: {
    location: location
    prefijo: prefijo
    tamanoNodo: tamanoNodo
    versionKubernetes: versionKubernetes
    etiquetas: etiquetas
  }
}

output grupo string = grupo.name
output acrNombre string = recursos.outputs.acrNombre
output acrLoginServer string = recursos.outputs.acrLoginServer
output aksNombre string = recursos.outputs.aksNombre
