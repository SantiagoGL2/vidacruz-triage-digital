# ADR 0009 — HTTPS en Azure: Ingress (ingress-nginx) con certificados de Let's Encrypt (cert-manager)

- **Estado:** aceptada (con una advertencia sobre el ciclo de vida de ingress-nginx; ver «Consecuencias»)
- **Fecha:** 2026-11-05
- **Alcance:** `k8s/overlays/azure`, `k8s/platform/`, `infra/deploy.*`; sustituye los `LoadBalancer` por servicio de [ADR 0008](0008-despliegue-en-aks.md)

## Contexto

El primer despliegue en AKS (ADR 0008) exponía cada microservicio con un `Service` tipo `LoadBalancer`: **HTTP sin TLS**, así que la
API key (ADR 0007) viajaba en claro. Hay que ofrecer HTTPS sin comprar un dominio, sin cambiar las rutas de la API y sin pasos
manuales en el despliegue. Restricciones del entorno: el único nombre DNS disponible es el que Azure da a una IP pública
(`<etiqueta>.eastus.cloudapp.azure.com`), el nodo es único y hay que cuidar la memoria.

## Decisión

1. **Un Ingress por servicio, con TLS, detrás de un único controlador `ingress-nginx`.** Solo el controlador es público; los
   `Service` de triage y del asistente pasan a `ClusterIP`. HTTP redirige a HTTPS (308) en todo host con TLS.
2. **Certificados de Let's Encrypt con cert-manager y el challenge HTTP-01.** Dos `ClusterIssuer` (`letsencrypt-prod` y
   `letsencrypt-staging`); la anotación `cert-manager.io/cluster-issuer` del Ingress pide y renueva el certificado solo. HTTP-01
   funciona con un nombre `*.cloudapp.azure.com` porque basta con que el puerto 80 del host sea alcanzable (no hace falta
   controlar la zona DNS, que es lo que exigiría DNS-01).
3. **Dos hosts, uno por servicio**, en vez de un host con rutas `/triage` y `/asistente`:
   * Ambos servicios sirven **en la raíz** `/swagger-ui.html`, `/v3/api-docs` y `/actuator/health`. Con un solo host chocarían, y
     separarlos habría exigido mover la documentación y los *probes* de cada servicio bajo un prefijo (cambios de configuración y de
     la lista de rutas públicas de seguridad), para ganar solo un nombre DNS menos.
   * Azure da **una etiqueta DNS por IP pública**. Por eso el controlador tiene **dos `Service` `LoadBalancer`** sobre los mismos
     pods (`ingress-nginx-controller` y `ingress-nginx-controller-asistente`), cada uno con su IP y su etiqueta; el controlador
     elige el backend por el `Host`. Son las mismas dos IPs que ya existían, así que **el costo no cambia**.
   * Las rutas de la API no cambian: `https://<host>/triage/pacientes`, `https://<host>/asistente/consulta`…
4. **Detrás del proxy, los servicios confían en `X-Forwarded-*`** (`server.forward-headers-strategy: framework` en el perfil `k8s`):
   sin eso Swagger UI y el contrato OpenAPI anunciarían `http://` y el navegador bloquearía «Try it out» por contenido mixto.
5. **Todo declarativo y sin pasos manuales:** `k8s/overlays/azure/ingress.yaml` (Ingress), `k8s/platform/ingress-nginx` (controlador
   y segundo `Service`), `k8s/platform/cert-manager/cluster-issuers.yaml` y el paso `plataforma` de `infra/deploy.*`, que instala
   cert-manager (versión fijada) e ingress-nginx y espera a las IP. `-Emisor staging` permite probar el flujo sin gastar el cupo de
   Let's Encrypt (sus certificados no son de confianza).
6. **El SSE de alertas** (`/triage/alertas/stream`) se sirve sin *buffering* y con *timeouts* largos en su Ingress.

## Alternativas consideradas

| Alternativa | Por qué no |
|---|---|
| **Add-on «application routing» de AKS** (NGINX gestionado por Microsoft) | Quita el mantenimiento del controlador, pero es un paso imperativo (`az aks approuting enable`) o un cambio de Bicep sobre un clúster ya creado, crea sus propios `LoadBalancer`/IP, su réplica por defecto pide bastante CPU para un nodo de 4 vCPU, y está basado en el mismo ingress-nginx (su soporte está ligado a su retirada). No simplifica el segundo host. |
| **Un host con rutas `/triage` y `/asistente`** | Colisión de Swagger UI y *health* (ver arriba). Es la opción más barata en DNS, pero obliga a tocar los servicios. |
| **Azure Application Gateway for Containers / Front Door** | Servicios administrados con costo mensual propio; desproporcionado para un entorno de prueba y para una demo. |
| **TLS en cada pod (Spring `server.ssl`)** | Certificados y renovación dentro de cada servicio, sin redirección ni punto único de entrada. |
| **Certificado autofirmado** | Los navegadores y `curl` lo rechazan; no resuelve el problema del evaluador. |
| **Gateway API en vez de Ingress** | Es el rumbo a largo plazo (ver abajo), pero cert-manager, los controladores y la documentación de AKS están mucho más maduros con Ingress; se deja como evolución. |

## Consecuencias

* **ingress-nginx fue retirado por la comunidad de Kubernetes** (repositorio archivado en marzo de 2026; la última versión es la
  1.15.1, que es la que se usa, con el manifiesto oficial vendorizado en `k8s/platform/ingress-nginx/ingress-nginx.yaml`). **Ya no
  recibirá correcciones de seguridad.** Es una elección consciente para este entorno de prueba, porque es el controlador pedido, el
  más conocido y el que cert-manager prueba más; **no debe llevarse tal cual a producción**. Ruta de salida: una implementación de
  **Gateway API** (Envoy Gateway, Cilium, Traefik o la de AKS) manteniendo cert-manager. Los Ingress de este repositorio son
  estándar, así que el cambio se limita a `k8s/platform/` y a los objetos de ruta.
* El certificado se renueva solo (cert-manager, a los 60 de 90 días). No se registra ningún correo en el emisor (ACME lo hace
  opcional y no se versionan datos personales): a cambio Let's Encrypt no avisa si la renovación falla.
* La migración desde los `LoadBalancer` antiguos tiene una **interrupción breve**: la etiqueta DNS solo puede estar en una IP, así
  que el despliegue primero convierte los `Service` a `ClusterIP` (libera IP y etiqueta) y después crea los del controlador.
* HTTP-01 exige que el puerto 80 del host sea alcanzable desde Internet y que el DNS resuelva a la IP del controlador; los límites
  de Let's Encrypt (5 fallos de validación por hora y host) hacen que conviene probar con `-Emisor staging` si algo falla.
* Costo adicional: **cero** (mismas dos IP públicas y un solo balanceador). Memoria adicional: unos 300 MiB (controlador +
  cert-manager), dentro del margen del nodo.
* **Incidente en el primer despliegue:** los `Ingress` se crearon en `default` porque el overlay no fijaba el namespace; Let's Encrypt sí emitió los certificados (HTTP-01 funcionó en ~1 minuto) pero el Ingress no veía los Services de `vidacruz`. Se corrigió con `namespace: vidacruz` en el overlay, se trasladaron los Secret TLS ya emitidos (cert-manager los adoptó sin reemitir, para no gastar cupo) y el CI valida que ningún recurso del overlay quede fuera del namespace.
* Sigue sin haber *NetworkPolicy*: cualquier pod del clúster puede hablar con cualquier otro (ver limitaciones del README).
