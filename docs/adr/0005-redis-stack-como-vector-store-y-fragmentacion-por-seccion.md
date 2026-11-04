# ADR 0005 — Redis Stack como vector store del RAG y fragmentación por sección

- **Estado:** aceptada
- **Fecha:** 2026-11-04
- **Alcance:** `clinical-assistant-service` (RF-04 parte A)

## Contexto

La consulta de protocolos usa RAG: hay que guardar los fragmentos del corpus con su embedding y recuperar los más parecidos a una pregunta. El enunciado deja libre el
vector store (in-memory, pgvector, Redis…) y pide documentarlo. Además hay que decidir **cómo se parte el corpus** en fragmentos.

El servicio ya usa Redis (Stack, con los módulos RediSearch y RedisJSON) para la vista de urgencias, y ya estaba en `docker-compose.yml`; el corpus es pequeño (3 protocolos,
7 secciones) pero debe poder crecer soltando archivos.

## Opciones consideradas — vector store

1. **In-memory** (`SimpleVectorStore`). Cero infraestructura, pero se pierde al reiniciar: cada arranque (y cada réplica) recalcularía todos los embeddings, es decir, pagaría llamadas
   al proveedor y quedaría sin RAG si éste está caído al arrancar. No comparte índice entre réplicas.
2. **pgvector (PostgreSQL).** Sólido y persistente, pero el asistente no usa PostgreSQL (es de triage): añadiría una base de datos, su driver bloqueante (o R2DBC con soporte limitado
   para el tipo `vector`) y otra pieza de despliegue, sólo para 7 fragmentos.
3. **Redis Stack** con el `RedisVectorStore` de Spring AI (índice RediSearch con búsqueda KNN/HNSW y distancia coseno).

## Decisión — vector store

**Redis Stack.** Reutiliza una pieza que el servicio ya necesita y que ya está desplegada (compose, k8s), es persistente (los embeddings sobreviven a reinicios y los comparten las
réplicas, así que reindexar no vuelve a llamar al proveedor), ofrece búsqueda KNN con umbral de similitud y filtros de metadatos, y su cliente (Jedis dentro de Spring AI) es bloqueante,
igual que las llamadas al LLM: ambos se envuelven en `boundedElastic`.

Detalles de la integración:

- El vector store se arma a mano (`RagConfig`/`ConstructorIndice`) y se **excluye** `RedisVectorStoreAutoConfiguration`: en Spring AI 1.1 exige un `JedisConnectionFactory` (la aplicación usa
  Lettuce reactivo), no declara los campos de metadatos que hay que devolver en la búsqueda y crea el índice en el arranque, de modo que Redis o el proveedor caídos tumbarían la aplicación.
  El índice lo crea la indexación en segundo plano, con reintentos.
- Índice (`asistente-protocolos-idx`) y claves (`asistente:protocolo:`) **separados** de los de la vista de urgencias (`asistente:urgencias:`).
- La puntuación de Spring AI es `(1 + coseno) / 2` (ortogonal = 0.5); el umbral se expresa en esa escala (ver `docs/asistente-rag.md`).

## Opciones consideradas — fragmentación

1. **Por número de caracteres / tokens** (ventanas con solapamiento). Genérica, pero parte apartados por la mitad, mezcla el final de una sección con el inicio de otra y hace que
   la cita «protocolo + sección» sea imprecisa o falsa.
2. **Un fragmento por protocolo.** Citas imprecisas (sólo el protocolo) y el contexto crece con material irrelevante a medida que los protocolos se alargan.
3. **Un fragmento por sección** (apartado del protocolo).

## Decisión — fragmentación

**Por sección.** El corpus está escrito en apartados con significado clínico propio (Definición, Medidas iniciales, Contraindicaciones…): una sección es la unidad natural de
respuesta y de cita, y permite validar la cita exacta (barrera 3 de RN-05) comparando `protocolo + sección` con lo recuperado. Cada fragmento lleva los metadatos
`protocolo`, `titulo`, `seccion`, `version` y `hash`, e **incluye el título del protocolo en su texto** (los apartados suelen no repetir el tema: «Reconfirmar la presión…» no menciona
«crisis hipertensiva»; con el título, la pregunta encuentra el fragmento). El id es determinista (`proto-001:medidas-iniciales`) y el `hash` cubre texto y versión, lo que permite una
indexación idempotente: sólo se recalcula lo que cambió y se eliminan los protocolos que ya no existen.

## Consecuencias

- (+) Citas exactas y verificables; índice persistente compartido; sin infraestructura nueva; agregar un protocolo es soltar un archivo `.md` en `resources/protocolos`.
- (+) La indexación idempotente y en segundo plano hace que el arranque no dependa del proveedor de embeddings.
- (−) Una sección muy larga produciría un fragmento grande (hoy todas son cortas). Si algún día hace falta, se subdivide dentro de la sección manteniendo el mismo par protocolo+sección en la cita.
- (−) Redis es ahora un punto único para la vista de urgencias **y** el RAG (mitigado: si cae, la consulta responde 503 y la vista también, ambos con mensaje claro; triage no se ve afectado).
- (−) Cambiar de modelo de embeddings (otra dimensión o espacio vectorial) obliga a recalibrar el umbral, borrar el índice y reindexar.
- (−) HNSW en Redis es aproximado; con 7 fragmentos da igual, con miles habría que revisar parámetros.
