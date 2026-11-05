#!/usr/bin/env bash
# Destruye TODO lo de VidaCruz en Azure: borra el grupo de recursos (AKS, ACR, discos, IP publicas, balanceadores).
# Irreversible: se pierden los datos de PostgreSQL, Kafka y Redis. Pide escribir el nombre del grupo (o usa --forzar).
#   ./infra/destroy.sh [--forzar] [--sin-esperar] [--conservar-secretos]
set -euo pipefail

GRUPO="${GRUPO:-rg-vidacruz-triage}"
PREFIJO="${PREFIJO:-vidacruz}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FORZAR=0; ESPERAR=1; CONSERVAR=0
for a in "$@"; do
  case "$a" in
    --forzar) FORZAR=1 ;;
    --sin-esperar) ESPERAR=0 ;;
    --conservar-secretos) CONSERVAR=1 ;;
    *) echo "Opcion desconocida: $a" >&2; exit 1 ;;
  esac
done

if [ "$(az group exists -n "$GRUPO")" != "true" ]; then
  echo "El grupo $GRUPO no existe: nada que borrar."
else
  printf '\033[31mSe va a BORRAR el grupo de recursos %s y todo su contenido:\033[0m\n' "$GRUPO"
  az resource list -g "$GRUPO" --query "[].{tipo:type, nombre:name}" -o table
  if [ "$FORZAR" -ne 1 ]; then
    read -r -p "Escribe el nombre del grupo para confirmar: " respuesta
    [ "$respuesta" = "$GRUPO" ] || { echo "Confirmacion incorrecta: no se borro nada." >&2; exit 1; }
  fi
  if [ "$ESPERAR" -eq 1 ]; then az group delete -n "$GRUPO" --yes; echo "Grupo borrado."
  else az group delete -n "$GRUPO" --yes --no-wait; echo "Borrado en curso (se completa en unos minutos)."; fi
fi

# Limpieza local: contexto de kubectl y registro del despliegue a nivel de suscripcion.
AKS="aks-${PREFIJO}-triage"
kubectl config delete-context "$AKS" >/dev/null 2>&1 || true
kubectl config delete-cluster "$AKS" >/dev/null 2>&1 || true
kubectl config unset "users.clusterUser_${GRUPO}_${AKS}" >/dev/null 2>&1 || true
az deployment sub delete -n "${PREFIJO}-infra" >/dev/null 2>&1 || true
if [ "$CONSERVAR" -ne 1 ]; then
  rm -f "$AQUI/.env.azure" "$AQUI/.ultimo-tag"
  echo "Eliminados infra/.env.azure y infra/.ultimo-tag (las claves se regeneran en el proximo despliegue)."
fi
