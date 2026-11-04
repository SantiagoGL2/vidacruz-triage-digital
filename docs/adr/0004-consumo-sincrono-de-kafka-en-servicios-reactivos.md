# ADR 0004 — Consumo de Kafka síncrono por registro en servicios reactivos

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** consumidores Kafka de `triage-service` (alertas, RF-06) y `clinical-assistant-service` (estado de urgencias, RF-04 B)

## Contexto

Los dos servicios son reactivos (WebFlux, Reactor, R2DBC / Lettuce) y consumen `triaje.eventos` con `spring-kafka`
(no `reactor-kafka`, en mantenimiento). Los casos de uso de esos consumidores devuelven `Mono`. La regla del proyecto es
no bloquear el event loop, así que la primera idea fue que el método `@KafkaListener` también devolviera un `Mono`:
spring-kafka lo admite desde la 3.2 y promete confirmar el offset al completarse.

Lo que exigen los consumidores:

1. **El offset solo se confirma cuando el evento terminó** de procesarse (Redis aplicó la vista, el canal de alerta recibió
   la alerta).
2. **Orden por paciente:** un `en_consulta` no puede aplicarse antes que su `registrado`.
3. **Errores bajo control:** reintento con backoff si es transitorio (Redis caído) y DLT si no se puede procesar, sin
   perder ni saltarse el mensaje.

## El problema con los listeners que devuelven `Mono` (spring-kafka 3.3.16)

Comprobado con tests de integración y leyendo el código del contenedor:

- **Un `Mono` que falla no llega al `DefaultErrorHandler`.** El adaptador del listener registra
  `Future, Mono, or suspend function was completed with an exception`, confirma el offset y sigue. El único camino que
  conecta ese fallo con un reintento es el de *retry topics* (`@RetryableTopic`); con un contenedor normal, el evento se
  pierde sin reintento ni DLT. El test «Redis caído» y el de la DLT del asistente fallaron exactamente así: el mensaje
  desaparecía.
- **El contenedor puede entregar el registro siguiente sin esperar a que termine el anterior** (es el modo asíncrono: los
  acks se encolan y se confirman en orden, pero el procesamiento se solapa). Si el registro *k* está reintentándose por un
  Redis caído, el *k+1* del mismo paciente se procesaría antes y el estado final dependería de una carrera.
- Los errores que se lanzan *antes* de devolver el `Mono` sí siguen la vía normal; por eso el problema pasaba
  desapercibido (los JSON ilegibles llegaban bien a la DLT) y solo aparecía con fallos dentro del procesamiento.

## Decisión

El método `@KafkaListener` es **síncrono y procesa un registro cada vez**: llama al caso de uso reactivo y espera su
resultado con `block(timeout)` **en el hilo del consumidor Kafka**.

```java
@KafkaListener(...)
void alRecibir(...) {
    casoDeUso.ejecutar(...).block(timeout);   // las RuntimeException salen tal cual
}
```

- Las excepciones salen por la vía normal del contenedor: `DefaultErrorHandler` con backoff exponencial para lo
  transitorio, DLT directa para lo no recuperable (`EventoInvalidoException`, errores de deserialización).
- `ack-mode: record`: el offset se confirma por registro, solo después de que `block` devolvió.
- El timeout (`…timeout-procesamiento`, 30 s por defecto) es solo un seguro; los clientes ya cortan antes (Redis 2 s).
- **Todo lo demás sigue siendo reactivo:** casos de uso, puertos, Redis, canales de alerta. Solo la frontera con el
  contenedor del consumidor espera.

## Por qué no rompe la regla de no bloquear el event loop

La regla protege a los hilos que atienden muchas conexiones con pocos hilos: los **event loops de Netty** y los
**schedulers de Reactor** (`parallel`, `single`). El hilo de un `@KafkaListener` es otro: lo crea el contenedor de
spring-kafka, es **dedicado a ese consumidor** y su trabajo es, por diseño, procesar un registro tras otro; esperar ahí no
quita capacidad a ningún otro trabajo, igual que `poll()` ya espera. Si los dos hilos fueran el mismo, bloquear sería un
error; no lo son, y BlockHound (que vigila los hilos no bloqueantes) no marca nada en estos listeners. La regla queda
precisada así en `CLAUDE.md`: *nunca bloquear el event loop ni los schedulers de Reactor; en hilos de consumidores Kafka se
permite `block(timeout)` para garantizar orden y confirmación de offsets*.

## Consecuencias

- (+) Orden por partición garantizado, confirmación de offset solo al terminar y errores siempre gestionados (reintento →
  DLT). Tests: Redis caído que se recupera, mensaje venenoso que termina en la DLT, `AlertasErrorInesperadoTests`.
- (+) Modelo mental simple y sin código propio de reintentos: se usa el `DefaultErrorHandler` estándar.
- (−) **El throughput queda limitado por partición:** un consumidor procesa un registro a la vez por partición. Para este
  volumen (eventos de urgencias, decenas por minuto) sobra; si hiciera falta más, se sube el número de particiones y de
  hilos (`concurrency`), que escalan en paralelo sin perder el orden por clave.
- (−) Mientras dura un reintento por fallo transitorio, esa partición no avanza (es lo que se quiere: no saltarse ni
  reordenar eventos). El backoff está acotado (`max-elapsed-time`) y luego el mensaje va a la DLT para no bloquear para
  siempre.
- (−) Es una excepción explícita a «nada de `.block()`»: debe quedar en el listener, nunca en casos de uso, adapters ni
  controllers.
- Si una versión futura de spring-kafka entrega los fallos de los listeners asíncronos al `DefaultErrorHandler` y serializa
  la entrega por partición, esta decisión se puede revisar; hasta entonces **no** cambiar a `Mono` sin repetir los tests de
  Redis caído y de DLT.
