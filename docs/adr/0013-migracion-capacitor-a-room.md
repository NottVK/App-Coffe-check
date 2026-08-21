# ADR-013 — Importar los datos de Capacitor a Room al primer arranque

- **Estado:** Aceptada
- **Fecha:** 2026-08-11
- **Decide:** Arquitectura de software

## Contexto

`CLAUDE.md` §2.1 y §12.5 tratan la preservación de datos locales como un problema
de **migraciones de Room**: esquema exportado, `MigrationTestHelper`, migraciones
incrementales probadas en ambos sentidos.

Ese encuadre no aplica aquí, porque la app publicada no usa Room. Usa
`@capacitor/preferences`, que en Android es un `SharedPreferences` de nombre
`CapacitorStorage`, y `@capacitor/filesystem` con `Directory.Data`, que es el
`filesDir` de la aplicación.

Concretamente (`src/app/services/photo.service.ts`):

| Qué | Dónde | Formato |
|---|---|---|
| Índice de fotos | `SharedPreferences("CapacitorStorage")`, clave `photos` | `[{webPath, filePath, webViewPath}]` en JSON |
| Fotos | `filesDir` | JPEG, nombre `<epochMillis>.jpeg` |

Esa galería es lo único que la app publicada guarda del usuario, y es trabajo
suyo: fotos tomadas en su lote, que él decidió conservar. La versión nueva arranca
con una base Room vacía. Sin una importación explícita, todo usuario que actualice
abre la app y ve que se le borró todo, sin aviso y sin forma de recuperarlo.

## Decisión

Se implementa una **importación única en el primer arranque** de la versión nueva,
en `:core:data`, ejecutada antes de componer la primera pantalla.

El esquema Room arranca en la versión 1 (`roomSchemaVersion: 0` en `PRESERVE.md`
refleja que antes no había ninguno). La importación **no es** una `Migration` de
Room: es un paso previo, con su propio registro de estado en DataStore.

Reglas de la implementación:

1. **Tolerante a datos malos.** JSON corrupto, campo ausente, ruta nula, entrada
   duplicada, archivo que ya no existe. Ninguno de esos casos aborta la
   importación: se descarta la entrada, se cuenta y se sigue. Una entrada mala no
   puede costar las otras treinta y nueve.
2. **Idempotente.** Interrumpirla a la mitad y reejecutarla produce el mismo
   estado final. Una segunda ejecución sobre una importación completa no duplica
   nada.
3. **No destructiva.** `CapacitorStorage` y los archivos originales **no se
   borran**. Si la importación salió mal, la versión siguiente todavía puede
   releerlos; si se borran, no hay vuelta atrás.
4. **Conservadora con el consentimiento.** Las filas importadas entran con
   `syncState = SKIPPED` y `gateOutcome = UNCERTAIN`. El usuario nunca dio
   consentimiento para que esas imágenes alimentaran el entrenamiento, y
   asumirlo retroactivamente sería exactamente lo que §12.2 prohíbe. Se le pedirá
   después, en contexto.
5. **`modelVersion = "legacy-tm-1.0"`**, para que esas filas nunca contaminen las
   métricas de exactitud de campo de una versión de modelo que no las produjo.

## Alternativas consideradas

**No migrar; empezar limpio.** Descartada. Es la opción barata y es la que quema
la confianza del usuario en el momento exacto en el que se le pide que confíe más
—registrarse, dar ubicación, consentir el uso de imágenes.

**Migrar en segundo plano con WorkManager.** Descartada. En el segmento de
dispositivos objetivo, las capas de fabricante suprimen el trabajo en segundo
plano de forma rutinaria: la galería aparecería «a veces». Además la primera
pantalla es el historial, así que el dato hace falta ya.

**Migrar por lotes bajo demanda, al desplazar el historial.** Descartada por
complejidad desproporcionada: el volumen esperado es de decenas de fotos, no
miles, y el coste de una importación completa es de milisegundos.

**Escribir un `Migration` de Room falso de 0 → 1.** Descartada: Room no puede leer
`SharedPreferences` ni el sistema de archivos desde una `Migration`, y forzarlo
produciría un artefacto que ninguna prueba de migración puede ejercitar.

## Consecuencias

**Positivas**
- El usuario que actualiza conserva su galería. La actualización se percibe como
  mejora, no como pérdida.
- Se conserva material fotográfico real de campo que, con consentimiento posterior,
  puede alimentar el dataset del modelo reentrenado.

**Negativas**
- Añade una ruta de código que solo se ejecuta una vez por instalación y que, por
  tanto, es fácil de romper sin que nadie lo note. Se mitiga con pruebas
  instrumentadas obligatorias (abajo) y con contadores registrados en telemetría.
- El primer arranque tras actualizar es más lento: hay que generar una miniatura
  de 256 px por foto, que la app anterior no tenía. Se muestra progreso.
- Se conservan dos copias de cada imagen —la original de Capacitor y la
  reprocesada— hasta que la purga de 90 días actúe. En un teléfono lleno eso
  puede molestar. Es el precio de no borrar nada hasta estar seguros.

## Pruebas obligatorias antes de publicar

No es una lista sugerida. `bundleRelease` no debería considerarse válido sin ella:

- Usuario con 0 fotos.
- Usuario con 1 foto.
- Usuario con 40 fotos.
- JSON cortado a media cadena.
- Entradas cuyo archivo ya no existe en `filesDir`.
- Importación interrumpida y reejecutada.
- Segunda ejecución tras una importación completa: cero duplicados.
- Disco lleno a mitad de la importación.

## Condición de revisión

Cuando la telemetría muestre que menos del 1 % de las instalaciones activas
conserva datos de `CapacitorStorage`, la ruta de importación puede retirarse en una
versión posterior. **No antes**, y nunca en la misma versión que la introduce.
