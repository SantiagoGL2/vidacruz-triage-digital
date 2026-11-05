# ADR 0007 — Autenticación por API key

- **Estado:** aceptada (para esta entrega; evolución propuesta al final)
- **Fecha:** 2026-11-04
- **Alcance:** `triage-service` y `clinical-assistant-service`

## Contexto

VidaCruz entrega dos backends sin frontend propio. Quienes los consumen son **sistemas**: el módulo de recepción que
registra pacientes y el tablero de urgencias que lee el estado y las alertas. No hay un flujo de login de personas en esta
entrega. El enunciado exige proteger los endpoints y deja elegir entre JWT y API key.

Lo que se necesita ahora: rechazar a quien no es un cliente conocido, sin añadir infraestructura (un proveedor de identidad)
que el resto del ejercicio no justifica, y sin romper el modelo reactivo (nada de llamadas bloqueantes por petición).

## Decisión

**Una API key por servicio**, enviada en el header `X-API-Key`.

- **Stateless:** cada petición se autentica sola; no hay sesión, ni cookie, ni CSRF (`SecurityContext` sin estado,
  `NoOpServerSecurityContextRepository`).
- **Un `WebFilter` de Spring Security** (`AuthenticationWebFilter`) extrae la clave, la valida y, si falla, responde `401`
  como `ProblemDetail` con el catálogo de errores (`no-autenticado`, ver `docs/errores.md`) y `WWW-Authenticate`.
- **Comparación en tiempo constante:** se calcula el SHA-256 de la clave esperada y de la recibida y se comparan con
  `MessageDigest.isEqual`. Con el hash ambas tienen la misma longitud, así que la comparación no filtra ni el largo de la
  clave ni cuántos caracteres iniciales coinciden.
- **Sin credenciales en el código:** la clave llega por variable de entorno (`TRIAGE_API_KEY`, `ASSISTANT_API_KEY`) desde
  `.env` en local y desde un **Kubernetes Secret** en el clúster. No tiene valor por defecto: sin ella el servicio no
  arranca.
- **Rutas públicas, solo estas:** `/actuator/health/**` (las probes de Docker/Kubernetes no llevan clave) y la documentación
  OpenAPI (`/v3/api-docs/**`, `/swagger-ui*`), que además se apaga en producción con `SWAGGER_HABILITADO=false`. Todo lo
  demás exige la clave, incluidos `/actuator/prometheus` y el stream SSE de alertas.
- Cada servicio tiene **su propia clave**: comprometer una no abre el otro. El asistente no llama a la API de triage (solo
  consume Kafka), así que no hay credencial entre servicios.

## Alternativas consideradas

| Alternativa | Por qué no ahora |
|---|---|
| **JWT con Keycloak o Microsoft Entra ID** | Es lo correcto cuando hay personas y roles, pero obliga a operar (o depender de) un proveedor de identidad, validar firmas/JWKS y definir claims y roles que el enunciado no pide. Se deja como evolución. |
| **JWT firmado por el propio servicio** | Habría que construir emisión, rotación de claves y revocación: más superficie de ataque que una clave estática para el mismo resultado, y sin las ventajas de un IdP real. |
| **mTLS entre cliente y servicio** | Fuerte para sistema-a-sistema, pero exige una PKI (emisión, renovación y revocación de certificados) y terminación TLS configurada en cada despliegue; desproporcionado para esta entrega. |
| **HTTP Basic** | Mismo modelo de secreto compartido, pero con usuario y contraseña que nadie necesita aquí y manejo más incómodo desde Swagger y scripts. |

## Consecuencias

**Positivas**

- Implementación pequeña, probada (`PacienteApiTests`, `EstadoUrgenciasApiTests`: sin clave y con clave incorrecta → 401) y
  sin latencia añadida: no hay llamadas externas por petición.
- Funciona igual en local, Docker Compose y Kubernetes; Swagger UI la admite con el botón **Authorize**.
- No introduce estado en los servicios, así que escalan en réplicas sin coordinación.

**Limitaciones (asumidas)**

- **Identifica a la aplicación, no a la persona.** Quien tenga la clave puede hacer todo lo que la API permite.
- **La auditoría no registra el usuario.** La tabla de auditoría guarda qué cambió y cuándo (RN-04), pero no quién lo pidió:
  con una clave compartida solo se podría registrar «el cliente», que no aporta trazabilidad individual.
- **No hay roles ni permisos finos:** un médico, recepción y el tablero tienen el mismo acceso por servicio. Por ejemplo,
  nada impide que un cliente de solo lectura (tablero) use `PATCH /triage/pacientes/{id}/estado` si conoce la clave.
- **Rotación manual:** cambiar la clave implica actualizar el Secret/`.env` y reiniciar el servicio, con una ventana en la
  que los clientes deben actualizarla a la vez. No hay dos claves válidas en paralelo.
- Una clave filtrada vale hasta que alguien la cambie; no caduca sola. No hay límite de peticiones por cliente.
- La clave viaja en cada petición: depende de que el tráfico externo vaya por TLS (terminado en el Ingress / gateway).

## Evolución propuesta para producción

1. **Microsoft Entra ID** (OAuth2/OIDC) como proveedor de identidad: los servicios pasan a ser *resource servers* que validan
   JWT (`spring-security-oauth2-resource-server`, JWKS en caché; la validación local no bloquea el event loop).
2. **Roles por perfil clínico** como claims/app roles (p. ej. recepción, enfermería de triaje, médico, tablero de solo
   lectura) y autorización por endpoint: registrar solo recepción/triaje, cambiar estado solo personal clínico, el tablero
   solo lectura y alertas.
3. **Usuario en la auditoría:** guardar el identificador del sujeto del token (`oid`/`sub`) en cada fila de auditoría y en el
   envelope de los eventos, para responder «quién lo hizo».
4. **Secretos en Azure Key Vault** (Secrets Store CSI Driver / Workload Identity en AKS) en lugar de Secrets estáticos, con
   **rotación** programada y, durante la transición, dos credenciales válidas a la vez.
5. Para sistema-a-sistema (p. ej. el tablero), *client credentials* de Entra ID en lugar de una clave estática; mTLS o
   Managed Identity si el entorno lo permite.
6. Límites de peticiones por cliente en el gateway (Azure API Management o Ingress).

La estructura actual lo facilita: la autenticación vive entera en `infrastructure.config.security`, los controllers no la
conocen y los casos de uso no reciben ningún dato de seguridad, así que cambiar el mecanismo no toca el dominio ni la
aplicación (solo habría que pasar el usuario al caso de uso para auditarlo).
