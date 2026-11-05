# ADR 0008 — Despliegue en Azure: AKS con las bases de datos dentro del clúster

- **Estado:** aceptada (entorno de prueba; alternativa de producción al final)
- **Fecha:** 2026-11-04
- **Alcance:** `infra/` (Bicep + scripts), `k8s/` (Kustomize), perfil `k8s` de ambos servicios
- **Guía operativa:** [`docs/despliegue-azure.md`](../despliegue-azure.md)

## Contexto

El enunciado pide manifests de Kubernetes y el proyecto necesita **Redis Stack** (RediSearch como vector store del RAG, ADR 0005).
Hay que desplegar en una suscripción de **prueba gratuita**: región `eastus`, 4 vCPU de cuota regional (y 4 por familia), sin
margen para un segundo nodo. La plataforma es: 2 servicios Spring, PostgreSQL, Kafka (KRaft) y Redis Stack, igual que en
`docker-compose.yml`.

## Decisión

1. **AKS, no Azure Container Apps.** Container Apps no es Kubernetes (no aplica el entregable de manifests) y los módulos de
   Redis administrados que ofrece Azure no incluyen RediSearch salvo el plan Enterprise (Azure Cache for Redis Enterprise /
   Azure Managed Redis con módulos), de otro orden de costo.
2. **PostgreSQL, Kafka y Redis Stack corren dentro del clúster** como `StatefulSet` con volumen persistente (Standard SSD),
   con las mismas imágenes y configuración que `docker-compose`. Un nodo cada uno, sin réplicas. Solo `ClusterIP`: nunca
   se exponen fuera del clúster.
3. **Un grupo de recursos** (`rg-vidacruz-triage`, `eastus`), **ACR Basic** y **AKS plan Free con un único nodo**. Sin
   monitoreo administrado (Log Analytics) para ahorrar costo y memoria. Todo en Bicep (`infra/`), con identidad administrada
   del clúster y rol **AcrPull** sobre el ACR (el clúster baja imágenes sin credenciales guardadas).
4. **Exposición pública:** un `Service` tipo `LoadBalancer` por servicio con
   `service.beta.kubernetes.io/azure-dns-label-name`, que da una URL `*.eastus.cloudapp.azure.com`. La sonda del balanceador
   apunta a `/actuator/health/readiness` (público); sin eso consultaría `/`, que responde 401, y lo daría por caído.
5. **Kustomize:** `k8s/base` (agnóstico de nube) + `k8s/overlays/azure` (imágenes del ACR, `LoadBalancer`, etiquetas DNS). Los
   nombres propios de la suscripción (ACR, etiqueta DNS, tag) son marcadores que `infra/deploy.*` sustituye al renderizar:
   no se versiona nada de la cuenta.
6. **Las imágenes de terceros** (postgres, kafka, redis-stack-server, busybox) se copian al ACR con `az acr import`:
   se evitan los límites de descarga anónima de Docker Hub y todo el clúster tira solo del ACR.
7. **Secrets** (`vidacruz-secrets`): los crea `infra/deploy.*` desde el `.env` local (`LLM_API_KEY`) y valores generados al
   azar (contraseñas de Postgres y Redis, API keys), guardados en `infra/.env.azure`. Ambos están en `.gitignore`; el repo solo
   trae `k8s/secret.example.yaml` sin valores.

### Desviaciones respecto al plan inicial (y por qué)

| Previsto | Real | Motivo |
|---|---|---|
| Nodo `Standard_B4ms` | **`Standard_D4s_v4`** (4 vCPU / 16 GiB) | El preflight de AKS rechaza la serie B en esta suscripción de prueba (`The VM size of Standard_B4ms is not allowed in your subscription`); la cuota no era el problema. Mismas vCPU y memoria; +0,63 USD/día. |
| `az acr build` (imágenes en la nube) | **`docker build --platform linux/amd64` + `docker push`** | **ACR Tasks está bloqueado en suscripciones de prueba** (`TasksOperationsNotAllowed`, solo se habilita por soporte). |
| Redis Stack completo | `redis-stack-server` | Sin RedisInsight (solo comodidad local): menos memoria. Mismo RediSearch. |

**En producción las imágenes las construiría el pipeline de CI** (GitHub Actions / Azure DevOps): construir, escanear, firmar
y publicar en el ACR con una identidad federada (OIDC), con el tag del commit; el despliegue consumiría ese tag. Construir
desde el portátil de una persona es una concesión de este entorno de prueba, no un proceso a conservar.

## Reparto de recursos del nodo (B4ms → D4s_v4: 4 vCPU, 16 GiB)

Capacidad asignable medida en el nodo: **3860 m de CPU y 14,3 GiB** (AKS reserva el resto para kubelet y el sistema).

| Componente | CPU request | Memoria request | Memoria límite |
|---|---|---|---|
| triage-service | 200 m (límite 1) | 768 MiB | 1 GiB |
| clinical-assistant-service | 200 m (límite 1) | 768 MiB | 1 GiB |
| kafka (heap 256–512 MiB) | 100 m | 768 MiB | 1,25 GiB |
| postgres | 100 m | 256 MiB | 512 MiB |
| redis-stack-server (`maxmemory` 400 MiB) | 100 m | 256 MiB | 640 MiB |
| Pods del sistema de AKS (coredns, kube-proxy, CSI, metrics-server, konnectivity…) | ≈ 0,9 CPU | ≈ 1 GiB | — |
| **Total pedido (medido)** | **1627 m (42 %)** | **3810 MiB (26 %)** | límites 115 % (sobre-asignado a propósito) |

Las *requests* dejan más de la mitad del nodo libre; los *límites* de memoria suman más que el nodo porque no todos los
procesos llegan a su tope a la vez. El uso real tras desplegar fue ≈ 1,8 GiB y 37 % de CPU (Kafka tiene un pico de CPU al
arrancar). Sin límite de CPU en las bases de datos (evita estrangulamiento); los servicios Java sí llevan límite de 1 CPU para
que la JVM dimensione sus hilos en consecuencia.

## Endurecimiento de los pods

- Servicios: `runAsNonRoot` con UID numérico 10001 (la imagen declara el usuario por nombre y Kubernetes necesita número),
  `readOnlyRootFilesystem: true` (con `emptyDir` en `/tmp`), `allowPrivilegeEscalation: false`, `capabilities: drop [ALL]`,
  `seccompProfile: RuntimeDefault`. Namespace con Pod Security `baseline` (enforce) y `restricted` (warn).
- Bases de datos: no root y sin privilegios, pero **sin** sistema de archivos de solo lectura (los entrypoints de postgres,
  kafka y redis escriben fuera de sus volúmenes).
- **`enableServiceLinks: false` en todos los pods.** Si no, Kubernetes inyecta `KAFKA_PORT=tcp://…` (la imagen de Kafka lo
  leería como configuración del broker) y `REDIS_PORT=tcp://…` (rompería `${REDIS_PORT:6379}` del asistente).
- Sondas `startup`/`liveness`/`readiness` sobre `/actuator/health/{liveness,readiness}`; `initContainers` que esperan a
  Postgres/Redis y Kafka; `preStop` de 5 s + `SHUTDOWN_TIMEOUT=25s` de Spring dentro de `terminationGracePeriodSeconds: 45`.
- Estrategia de despliegue `maxSurge: 0`: no hay cuota para un pod extra durante la actualización.

## Alternativas consideradas

- **Container Apps:** más simple de operar, pero sin manifests de Kubernetes y sin Redis con RediSearch administrado barato.
- **Más nodos / nodo mayor:** descartado por cuota (4 vCPU en total).
- **Kafka y Postgres administrados ya:** correcto para producción (ver abajo), pero cuesta más que todo el entorno de prueba y
  el objetivo aquí es reproducir `docker-compose` en Kubernetes.

## Consecuencias y limitaciones

- **Un nodo, una réplica de todo:** sin alta disponibilidad. Reiniciar el nodo o actualizar AKS implica indisponibilidad
  (y una actualización de AKS no cabe en la cuota: necesita un nodo extra temporal; `upgradeChannel: none`).
- **Datos en discos del clúster:** `Delete` como política de reclamación y sin copias de seguridad automáticas. Borrar el grupo
  de recursos destruye PostgreSQL, Kafka y Redis. Aceptable para prueba, no para producción.
- **HTTP sin TLS** en las URLs públicas: la API key viaja en claro. Para algo más que una demo, un Ingress con certificado
  (cert-manager + Let's Encrypt o Application Gateway).
- **El disco del sistema operativo es "Managed" (Premium P6), no efímero**: se presupuestó en el caso peor.
- Los secretos son `Secret` de Kubernetes (codificados en base64 en etcd, sin cifrado con clave propia).
- Las URLs públicas tienen autenticación por API key ([ADR 0007](0007-autenticacion-por-api-key.md)) pero Swagger UI está
  encendido en este entorno (`SWAGGER_HABILITADO=true`) para poder probar; en producción se apaga.

## Evolución propuesta para producción

| Hoy (dentro del clúster) | Producción | Costo orientativo (eastus) |
|---|---|---|
| PostgreSQL 16 en StatefulSet | **Azure Database for PostgreSQL Flexible Server** (HA, backups, parches). Misma conexión R2DBC/Flyway. | Burstable B4ms: 0,272 USD/h ≈ 196 USD/mes de cómputo (existen tallas menores); más almacenamiento y HA |
| Kafka KRaft en StatefulSet | **Event Hubs con API Kafka** (nivel Standard o superior; mismo cliente `spring-kafka`, SASL/TLS). Revisar retención: el asistente reconstruye la vista desde `earliest`. | Standard: 0,03 USD/h por unidad de rendimiento ≈ 22 USD/mes + 0,028 USD por millón de eventos |
| Redis Stack en StatefulSet | **Azure Managed Redis** con módulo RediSearch (verificar que el tamaño elegido lo soporte) | Balanced B0: 0,016 USD/h ≈ 12 USD/mes; B3: 0,065 USD/h ≈ 47 USD/mes |
| Secrets de Kubernetes | **Azure Key Vault + Secrets Store CSI Driver** con Workload Identity; rotación programada | Centavos por operación |
| Auth por API key | Entra ID con roles (ADR 0007) | — |
| `az`/`docker` desde un portátil | **Pipeline de CI/CD** (build, escaneo, push al ACR por OIDC, `kubectl apply -k`) | — |
| LoadBalancer + HTTP | Ingress/Application Gateway con TLS y WAF; Swagger apagado | Según SKU |
| 1 nodo | Node pool de sistema + de usuario, varias réplicas, `PodDisruptionBudget`, autoscaling | Según tamaño |

Los precios salen de la API de precios de Azure (consultada el 2026-11-04) y son una guía de orden de magnitud, no una oferta.
