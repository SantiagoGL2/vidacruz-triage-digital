// Recursos dentro del grupo: Azure Container Registry (Basic) y AKS (plan Free, un nodo) con permiso AcrPull.
targetScope = 'resourceGroup'

param location string
param prefijo string
param tamanoNodo string
param versionKubernetes string
param etiquetas object

// Nombres globales unicos (ACR): prefijo + hash estable de suscripcion y grupo (mismo valor en cada despliegue = idempotente).
var acrNombre = 'acr${prefijo}${uniqueString(subscription().subscriptionId, resourceGroup().id)}'
var aksNombre = 'aks-${prefijo}-triage'

// ACR Basic: ~5 USD/mes, 10 GiB. Sin usuario administrador: AKS entra con su identidad (AcrPull), y az acr build con la del operador.
resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrNombre
  location: location
  tags: etiquetas
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    publicNetworkAccess: 'Enabled'
  }
}

// AKS plan Free (sin SLA del plano de control, sin costo) con UN nodo: no hay cuota para mas ni para un surge de actualizacion.
resource aks 'Microsoft.ContainerService/managedClusters@2024-09-01' = {
  name: aksNombre
  location: location
  tags: etiquetas
  sku: {
    name: 'Base'
    tier: 'Free'
  }
  identity: {
    type: 'SystemAssigned'
  }
  properties: {
    dnsPrefix: aksNombre
    kubernetesVersion: empty(versionKubernetes) ? null : versionKubernetes
    enableRBAC: true
    agentPoolProfiles: [
      {
        name: 'system'
        mode: 'System'
        count: 1
        vmSize: tamanoNodo
        osType: 'Linux'
        osDiskSizeGB: 64
        type: 'VirtualMachineScaleSets'
        enableAutoScaling: false
        maxPods: 60
      }
    ]
    networkProfile: {
      networkPlugin: 'azure'
      networkPluginMode: 'overlay'
      loadBalancerSku: 'standard'
      outboundType: 'loadBalancer'
    }
    // Sin addon de monitoreo (Log Analytics): ahorra costo y memoria del nodo; las metricas se exponen en /actuator/prometheus.
    autoUpgradeProfile: {
      upgradeChannel: 'none'
    }
  }
}

// El kubelet de AKS puede bajar imagenes del ACR (AcrPull) sin credenciales almacenadas.
var rolAcrPull = subscriptionResourceId('Microsoft.Authorization/roleDefinitions', '7f951dda-4ed3-4680-a7ca-43fe172d538d')

resource permisoAcrPull 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(acr.id, aks.id, rolAcrPull)
  scope: acr
  properties: {
    roleDefinitionId: rolAcrPull
    principalId: aks.properties.identityProfile.kubeletidentity.objectId
    principalType: 'ServicePrincipal'
  }
}

output acrNombre string = acr.name
output acrLoginServer string = acr.properties.loginServer
output aksNombre string = aks.name
