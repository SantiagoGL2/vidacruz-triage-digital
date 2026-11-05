#!/usr/bin/env bash
# Despliega VidaCruz Triage en Azure (ACR + AKS) por pasos. Equivalente a infra/deploy.ps1. Idempotente.
#
#   ./infra/deploy.sh cuota|plan|infra|imagenes|credenciales|secretos|aplicar|plataforma|verificar|todo
#
# HTTPS: los Services de los microservicios son ClusterIP; lo publico es el Ingress (un host por servicio con certificado de
# Let's Encrypt). 'plataforma' instala cert-manager + ingress-nginx. EMISOR=prod|staging elige el ClusterIssuer (staging para probar).
#
# Variables opcionales: REGION (eastus) PREFIJO (vidacruz) GRUPO (rg-vidacruz-triage) TALLA_NODO (Standard_D4s_v4) NAMESPACE (vidacruz)
# Secretos: LLM_API_KEY sale del entorno o del .env de la raiz. POSTGRES_PASSWORD, REDIS_PASSWORD, TRIAGE_API_KEY y
# ASSISTANT_API_KEY se generan al azar la primera vez y se guardan en infra/.env.azure (ignorado por git).
set -euo pipefail

PASO="${1:-todo}"
REGION="${REGION:-eastus}"
PREFIJO="${PREFIJO:-vidacruz}"
GRUPO="${GRUPO:-rg-vidacruz-triage}"
TALLA_NODO="${TALLA_NODO:-Standard_D4s_v4}"
NAMESPACE="${NAMESPACE:-vidacruz}"
EMISOR="${EMISOR:-prod}"
VERSION_CERT_MANAGER="${VERSION_CERT_MANAGER:-v1.21.3}"

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(dirname "$AQUI")"
DESPLIEGUE="${PREFIJO}-infra"
ARCHIVO_SECRETOS="$AQUI/.env.azure"

escribir() { printf '\033[36m==> %s\033[0m\n' "$*"; }
falla() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# Lee VALOR de CLAVE en un archivo KEY=VALUE (sin imprimirlo).
leer_env() { [ -f "$2" ] && grep -E "^$1=" "$2" | tail -n1 | cut -d= -f2- | tr -d '\r"'"'" || true; }
nuevo_secreto() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

salidas() {
  local json
  local base="properties.outputs"
  ACR="$(az deployment sub show -n "$DESPLIEGUE" --query "$base.acrNombre.value" -o tsv 2>/dev/null | tr -d '\r')" || ACR=""
  [ -n "$ACR" ] || falla "No hay despliegue '$DESPLIEGUE'. Ejecuta primero: $0 infra"
  ACR_SERVER="$(az deployment sub show -n "$DESPLIEGUE" --query "$base.acrLoginServer.value" -o tsv | tr -d '\r')"
  AKS="$(az deployment sub show -n "$DESPLIEGUE" --query "$base.aksNombre.value" -o tsv | tr -d '\r')"
  GRUPO_REAL="$(az deployment sub show -n "$DESPLIEGUE" --query "$base.grupo.value" -o tsv | tr -d '\r')"
  SUFIJO="${ACR: -6}"
  ETIQUETA_TRIAGE="${PREFIJO}-triage-${SUFIJO}"
  ETIQUETA_ASISTENTE="${PREFIJO}-asistente-${SUFIJO}"
  HOST_TRIAGE="${ETIQUETA_TRIAGE}.${REGION}.cloudapp.azure.com"
  HOST_ASISTENTE="${ETIQUETA_ASISTENTE}.${REGION}.cloudapp.azure.com"
}

etiqueta_imagen() {
  local hash; hash="$(git -C "$RAIZ" rev-parse --short HEAD)"
  if [ -n "$(git -C "$RAIZ" status --porcelain)" ]; then hash="${hash}-wip$(date +%Y%m%d%H%M)"; fi
  echo "$hash"
}

paso_cuota() {
  escribir "Validando cuota en $REGION para $TALLA_NODO (solo lectura)"
  az account show --query '{suscripcion:name, estado:state, usuario:user.name}' -o table
  # Con el clúster ya creado, su nodo ocupa toda la cuota: validarla de nuevo daria un falso "insuficiente".
  if az aks show -g "$GRUPO" -n "aks-${PREFIJO}-triage" --query name -o tsv >/dev/null 2>&1; then
    echo "AKS ya existe en $GRUPO: se omite la validacion de cuota (el nodo actual ya la ocupa)."
    return
  fi
  local vcpus familia
  vcpus="$(az vm list-sizes -l "$REGION" --query "[?name=='$TALLA_NODO'].numberOfCores | [0]" -o tsv --only-show-errors 2>/dev/null)"
  [ -n "$vcpus" ] || falla "La talla $TALLA_NODO no existe en $REGION."
  case "$TALLA_NODO" in
    Standard_D4s_v4) familia=standardDSv4Family ;;
    Standard_D4as_v7) familia=StandardDasv7Family ;;
    Standard_B4ms) familia=standardBSFamily ;;
    *) falla "Agrega la familia de $TALLA_NODO a este script (az vm list-usage -l $REGION)." ;;
  esac
  local libre_total libre_fam
  # JMESPath no resta: se piden limite y uso y se calcula aqui.
  libre_total="$(az vm list-usage -l "$REGION" --query "[?name.value=='cores'] | [0].[limit, currentValue]" -o tsv | tr -d '\r' | awk 'NR==1{l=$1} NR==2{u=$1} END{print l-u}')"
  libre_fam="$(az vm list-usage -l "$REGION" --query "[?name.value=='$familia'] | [0].[limit, currentValue]" -o tsv | tr -d '\r' | awk 'NR==1{l=$1} NR==2{u=$1} END{print l-u}')"
  echo "Talla $TALLA_NODO: $vcpus vCPU, familia $familia | libres: regional=$libre_total familia=$libre_fam"
  { [ "${libre_total:-0}" -ge "$vcpus" ] && [ "${libre_fam:-0}" -ge "$vcpus" ]; } || falla "Cuota insuficiente: se necesitan $vcpus vCPU libres."
  for p in Microsoft.ContainerService Microsoft.ContainerRegistry Microsoft.Network Microsoft.Compute Microsoft.Storage; do
    [ "$(az provider show -n "$p" --query registrationState -o tsv)" = "Registered" ] || falla "Proveedor $p sin registrar: az provider register -n $p --wait"
  done
  echo "OK: cuota y proveedores."
}

parametros_bicep() {
  PARAMS=(-l "$REGION" -n "$DESPLIEGUE" -f "$AQUI/main.bicep" -p "location=$REGION" "prefijo=$PREFIJO" "nombreGrupo=$GRUPO" "tamanoNodo=$TALLA_NODO")
}

paso_plan() {
  escribir "what-if de Bicep (no crea nada)"
  parametros_bicep
  # Resumen (tipo y nombre): el JSON completo trae el ID de la suscripcion.
  az deployment sub what-if "${PARAMS[@]}" --no-pretty-print --only-show-errors \
    --query "changes[].[changeType, after.type || before.type, after.name || before.name]" -o tsv | tr -d '\r' \
    | awk -F'\t' '{ n = ($2 == "Microsoft.Authorization/roleAssignments") ? "(AcrPull para el kubelet de AKS)" : $3;
                    printf "  %-8s %-52s %s\n", $1, $2, n; t++ } END { printf "Total: %d recurso(s).\n", t }'
}

paso_infra() {
  escribir "Creando/actualizando infraestructura en $REGION (grupo $GRUPO) - ESTO GENERA COSTO"
  parametros_bicep
  az deployment sub create "${PARAMS[@]}" --query properties.outputs -o json --only-show-errors
  salidas
  echo "ACR: $ACR_SERVER | AKS: $AKS | Grupo: $GRUPO_REAL"
}

paso_imagenes() {
  salidas
  local tag; tag="$(etiqueta_imagen)"
  # ACR Tasks (az acr build) esta bloqueado en suscripciones de prueba (TasksOperationsNotAllowed): se construye con Docker
  # local y se sube al ACR. En produccion lo haria el pipeline de CI (ADR 0008).
  escribir "Comprobando que Docker responde"
  docker version --format 'cliente {{.Client.Version}} / servidor {{.Server.Version}}' || falla "Docker no responde: abre Docker Desktop (o reanudalo) y reintenta."
  escribir "Login en el ACR $ACR"
  az acr login -n "$ACR" --only-show-errors
  escribir "Construyendo (linux/amd64) y subiendo imagenes, tag $tag"
  local servicio
  for servicio in triage-service clinical-assistant-service; do
    docker build --platform linux/amd64 -t "$ACR_SERVER/$servicio:$tag" "$RAIZ/$servicio"
    docker push "$ACR_SERVER/$servicio:$tag"
  done
  echo "$tag" > "$AQUI/.ultimo-tag"

  escribir "Copiando imagenes de terceros al ACR (az acr import; evita limites de Docker Hub)"
  local par origen destino repo etiqueta
  for par in "docker.io/library/postgres:16-alpine=postgres:16-alpine" \
             "docker.io/apache/kafka:3.9.1=kafka:3.9.1" \
             "docker.io/redis/redis-stack-server:7.4.0-v3=redis-stack-server:7.4.0-v3" \
             "docker.io/library/busybox:1.37=busybox:1.37"; do
    origen="${par%%=*}"; destino="${par#*=}"; repo="${destino%%:*}"; etiqueta="${destino#*:}"
    if az acr repository show-tags -n "$ACR" --repository "$repo" -o tsv 2>/dev/null | grep -qx "$etiqueta"; then
      echo "  ya existe $destino"
    else
      az acr import -n "$ACR" --source "$origen" -t "$destino" --only-show-errors
    fi
  done
}

paso_credenciales() {
  salidas
  escribir "Obteniendo credenciales de AKS (kubeconfig local)"
  az aks get-credentials -g "$GRUPO_REAL" -n "$AKS" --overwrite-existing --only-show-errors
  kubectl get nodes -o wide
}

paso_secretos() {
  escribir "Creando el Secret vidacruz-secrets en el namespace $NAMESPACE (los valores no se imprimen)"
  touch "$ARCHIVO_SECRETOS"; chmod 600 "$ARCHIVO_SECRETOS" 2>/dev/null || true
  local k
  for k in POSTGRES_PASSWORD REDIS_PASSWORD TRIAGE_API_KEY ASSISTANT_API_KEY; do
    if [ -z "$(leer_env "$k" "$ARCHIVO_SECRETOS")" ]; then
      echo "$k=$(nuevo_secreto)" >> "$ARCHIVO_SECRETOS"
      echo "  generado $k (guardado en infra/.env.azure)"
    fi
  done
  local llm="${LLM_API_KEY:-$(leer_env LLM_API_KEY "$RAIZ/.env")}"
  case "$llm" in ""|change-me*) falla "Falta LLM_API_KEY (variable de entorno o .env de la raiz) con una clave real." ;; esac

  local tmp; tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
  {
    for k in POSTGRES_PASSWORD REDIS_PASSWORD TRIAGE_API_KEY ASSISTANT_API_KEY; do echo "$k=$(leer_env "$k" "$ARCHIVO_SECRETOS")"; done
    echo "LLM_API_KEY=$llm"
  } > "$tmp"
  kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  kubectl -n "$NAMESPACE" create secret generic vidacruz-secrets --from-env-file="$tmp" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$tmp"
  echo "OK: Secret vidacruz-secrets aplicado."
}

paso_aplicar() {
  salidas
  [ -f "$AQUI/.ultimo-tag" ] || falla "No hay imagenes construidas. Ejecuta primero: $0 imagenes"
  local tag; tag="$(tr -d '\r\n' < "$AQUI/.ultimo-tag")"
  escribir "Aplicando k8s/overlays/azure (imagenes $tag)"
  # Un Job es inmutable: se recrea en cada despliegue (crear topics es idempotente).
  kubectl -n "$NAMESPACE" delete job kafka-init --ignore-not-found >/dev/null
  kubectl kustomize "$RAIZ/k8s/overlays/azure" \
    | sed -e "s|ACR_LOGIN_SERVER|$ACR_SERVER|g" -e "s|IMAGE_TAG|$tag|g" \
          -e "s|HOST_TRIAGE|$HOST_TRIAGE|g" -e "s|HOST_ASISTENTE|$HOST_ASISTENTE|g" -e "s|CLUSTER_ISSUER|letsencrypt-$EMISOR|g" \
    | kubectl apply -f -
  escribir "Esperando a que todo este listo"
  local ss d
  for ss in postgres kafka redis; do kubectl -n "$NAMESPACE" rollout status "statefulset/$ss" --timeout=300s; done
  kubectl -n "$NAMESPACE" wait --for=condition=complete job/kafka-init --timeout=240s
  for d in triage-service clinical-assistant-service; do kubectl -n "$NAMESPACE" rollout status "deployment/$d" --timeout=420s; done
}

esperar_ip() {
  local ip i ns="${2:-$NAMESPACE}"
  for i in $(seq 1 90); do
    ip="$(kubectl -n "$ns" get svc "$1" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
    [ -n "$ip" ] && { echo "$ip"; return; }
    sleep 5
  done
  falla "El Service $ns/$1 no obtuvo IP publica a tiempo."
}

paso_plataforma() {
  salidas
  escribir "Instalando cert-manager $VERSION_CERT_MANAGER (certificados de Let's Encrypt)"
  kubectl apply -f "https://github.com/cert-manager/cert-manager/releases/download/$VERSION_CERT_MANAGER/cert-manager.yaml"
  local d
  for d in cert-manager cert-manager-cainjector cert-manager-webhook; do
    kubectl -n cert-manager rollout status "deployment/$d" --timeout=300s
  done

  escribir "Instalando ingress-nginx (un controlador, dos IP publicas con su etiqueta DNS)"
  kubectl kustomize "$RAIZ/k8s/platform/ingress-nginx" \
    | sed -e "s|DNS_LABEL_TRIAGE|$ETIQUETA_TRIAGE|g" -e "s|DNS_LABEL_ASISTENTE|$ETIQUETA_ASISTENTE|g" \
    | kubectl apply -f -
  kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s

  escribir "Emisores de Let's Encrypt (HTTP-01)"
  local i
  for i in $(seq 1 12); do   # el webhook de cert-manager puede tardar unos segundos en aceptarlos
    kubectl apply -f "$RAIZ/k8s/platform/cert-manager/cluster-issuers.yaml" && break
    [ "$i" -eq 12 ] && falla "No se pudieron crear los ClusterIssuer"
    sleep 5
  done

  escribir "Esperando las IP publicas del controlador (las etiquetas DNS pasan de los antiguos LoadBalancer a estas)"
  echo "  $HOST_TRIAGE -> $(esperar_ip ingress-nginx-controller ingress-nginx)"
  echo "  $HOST_ASISTENTE -> $(esperar_ip ingress-nginx-controller-asistente ingress-nginx)"
}

paso_verificar() {
  salidas
  escribir "kubectl get pods,svc,ingress,certificate -n $NAMESPACE"
  kubectl get pods,svc,ingress,certificate -n "$NAMESPACE" -o wide
  escribir "kubectl get svc -n ingress-nginx"
  kubectl get svc -n ingress-nginx
  escribir "Esperando los certificados (cert-manager + Let's Encrypt, suele tardar menos de un minuto)"
  if ! kubectl -n "$NAMESPACE" wait --for=condition=Ready certificate/triage-tls certificate/asistente-tls --timeout=420s; then
    kubectl -n "$NAMESPACE" get certificate,certificaterequest,order,challenge || true
    falla "Certificados no listos (revisa los challenge: DNS, puerto 80 y limites de Let's Encrypt)."
  fi
  local urlt="https://$HOST_TRIAGE" urla="https://$HOST_ASISTENTE" u
  echo; echo "triage-service             : $urlt"
  echo "clinical-assistant-service : $urla"
  echo "Swagger UI                 : $urlt/swagger-ui.html  |  $urla/swagger-ui.html"
  for u in "$urlt" "$urla"; do
    echo "  $u -> $(curl -s -m 20 "$u/actuator/health/readiness" || echo sin respuesta)"
    echo "    http -> HTTP $(curl -s -o /dev/null -m 20 -w '%{http_code}' "${u/https:/http:}/actuator/health/liveness") (redireccion a https esperada: 301/308)"
  done
  echo "Las API keys estan en infra/.env.azure (TRIAGE_API_KEY, ASSISTANT_API_KEY)."
}

case "$PASO" in
  cuota) paso_cuota ;;
  plan) paso_plan ;;
  infra) paso_infra ;;
  imagenes) paso_imagenes ;;
  credenciales) paso_credenciales ;;
  secretos) paso_secretos ;;
  aplicar) paso_aplicar ;;
  plataforma) paso_plataforma ;;
  verificar) paso_verificar ;;
  todo) paso_cuota; paso_infra; paso_imagenes; paso_credenciales; paso_secretos; paso_aplicar; paso_plataforma; paso_verificar ;;
  *) falla "Paso desconocido: $PASO (cuota|plan|infra|imagenes|credenciales|secretos|aplicar|verificar|todo)" ;;
esac
