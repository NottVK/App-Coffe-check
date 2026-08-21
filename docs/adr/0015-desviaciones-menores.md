# ADR-015 — Dos desviaciones menores de estructura

- **Estado:** Aceptada
- **Fecha:** 2026-08-11
- **Decide:** Arquitectura de software

Se agrupan aquí dos desviaciones pequeñas respecto a `CLAUDE.md` §12.2 y §15.1.
Ninguna cambia una decisión de diseño; ambas se registran porque §1 exige que toda
desviación sea un ADR y no un cambio silencioso.

---

## 1. Módulo `:core:data`, no previsto en la lista de módulos

**Contexto.** §12.2 lista los módulos Gradle y no incluye ninguno donde vivan las
**implementaciones** de los puertos de repositorio. §6.2 del documento de
arquitectura sí describe la capa («DATOS — RepositoryImpl · Room DAO · LiteRT ·
Retrofit · DataStore»), pero no le asigna módulo.

Las opciones eran tres: meterlas en `:app`, repartirlas entre `:core:database`,
`:core:storage` y `:network:api`, o crear un módulo.

**Decisión.** Se crea `:core:data`.

**Por qué no `:app`.** Convierte el módulo de ensamblaje en el módulo más grande
del proyecto y hace que cualquier cambio de repositorio recompile todo.

**Por qué no repartirlas.** Un `CaptureRepositoryImpl` necesita a la vez el DAO de
Room, el `ImageStore` y el reloj. Colocarlo en `:core:database` obliga a ese módulo
a depender de `:core:storage`, y el grafo deja de tener una dirección clara.

**Consecuencia.** Un módulo más en el grafo. `:core:data` depende de
`:core:database`, `:core:storage`, `:core:datastore`, `:ml:registry` y
`:network:api`, y **ningún `:feature:*` depende de él**: los features solo ven los
puertos de `:core:model`, que es lo que sostiene la regla de que la UI no habla con
la red.

---

## 2. DataStore con serializador propio en vez de Proto DataStore

**Contexto.** §15.1 especifica «Room, DataStore Proto». El puntero de ranura de
modelo activa (`ModelSlotStore`) es el dato más delicado del subsistema de modelos:
escribir ahí es el único punto de no retorno del procedimiento de actualización
(§8.3).

**Decisión.** Se usa `DataStore<T>` de DataStore **Core** con un `Serializer<T>`
propio basado en kotlinx.serialization, en lugar de protobuf.

**Por qué.** Las garantías que hacen falta —tipado, escritura atómica sobre archivo
temporal más renombrado, transaccionalidad, valor por defecto ante corrupción— las
da DataStore Core y son idénticas con cualquier serializador. Lo que aporta
protobuf en este caso concreto es el plugin de Gradle que descarga `protoc`, un
binario nativo distinto por plataforma, dentro de un proyecto cuyo objetivo
declarado es tener builds reproducibles en contenedor.

kotlinx.serialization ya es dependencia del proyecto por el cliente HTTP, así que
no añade nada nuevo al grafo.

**Alternativa descartada.** Preferences DataStore para todo. No sirve para el
puntero de ranura: `ModelSlotPointer` es un agregado de siete campos que se
escriben **juntos o no se escriben**. Con claves sueltas, un corte de energía entre
dos `edit` deja el puntero apuntando a una ranura con la versión de la otra, que es
precisamente el fallo que el esquema A/B existe para impedir.

Preferences DataStore **sí** se usa para las preferencias del usuario, donde los
campos son independientes y no hay invariante entre ellos.

**Consecuencia.** El JSON es más grande que el protobuf equivalente. Hablamos de un
archivo de menos de 300 bytes que se lee una vez por arranque: irrelevante.

Si en el futuro apareciera un dato de alta frecuencia en DataStore, se reevalúa.
