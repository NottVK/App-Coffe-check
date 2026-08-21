# ADR-014 — Portar el modelo heredado con las compuertas 2 y 3 desactivadas y visibles

- **Estado:** Aceptada
- **Fecha:** 2026-08-11
- **Decide:** Arquitectura de software + responsable de ML

## Contexto

El producto se sostiene sobre una tesis, la primera de las tres de §1.2: **la
incertidumbre es una funcionalidad, no un defecto.** El modelo tiene que poder
decir «no sé», porque una predicción errónea con confianza alta provoca una
fumigación innecesaria que el productor no puede pagar.

El mecanismo que implementa esa tesis es el sistema de tres compuertas de §7.2, y
depende de que el modelo exporte **dos cabezas**: logits y embedding.

El modelo publicado hoy no puede hacerlo. Es un Teachable Machine ejecutado con
TensorFlow.js dentro de la WebView:

| Aspecto | Modelo publicado | Lo que exige §7 |
|---|---|---|
| Entrada | 224×224 ✅ | 224×224 |
| Salidas | Solo logits ❌ | Logits **+ embedding** (128–256 dim) |
| Clase de rechazo | **No existe** ❌ | Obligatoria |
| Calibración | Ninguna ❌ | Temperatura, τ, δ, umbral OOD, estadísticas por clase |
| Clases | 6 | 8–12 + rechazo |
| Formato | TF.js (`model.json` + `weights.bin`, 2,5 MB) | LiteRT INT8, 4–9 MB |

Sin embedding no hay distancia de Mahalanobis, luego **la compuerta 2 no puede
existir**. Sin artefactos de calibración no hay temperatura ni umbrales, luego
**la compuerta 3 tampoco**. Y sin clase de rechazo el modelo está obligado a elegir
una de seis enfermedades ante una hoja sana.

Esto no es un problema que introduzca el refactor: es el error prohibido nº 3 y el
nº 4 (§23) ya presentes en producción hoy.

## Decisión

El refactor **porta el modelo actual convertido a LiteRT** para no dejar la app sin
capacidad de escaneo, y **desactiva explícitamente las compuertas 2 y 3**, con tres
condiciones no negociables:

1. **La compuerta 1 (calidad) sí se implementa completa.** No depende del modelo:
   varianza del laplaciano, luminancia y heurística de contenido vegetal. Aporta
   valor desde el día uno y protege el dataset.

2. **La interfaz lo dice.** Cuando `CalibrationParams` no está presente, la
   pantalla de resultado muestra la predicción acompañada del aviso de que este
   reconocedor todavía no sabe reconocer cuándo no sabe, y ofrece igualmente la
   Solicitud de Revisión. No se oculta la limitación con letra pequeña.

3. **`UncertaintyGate` falla al activar un modelo sin calibración, no al usarlo.**
   `InstalledModel` exige `CalibrationParams`, y `activate()` rechaza el artefacto
   si `isConsistentWith(classCount)` devuelve falso. El modelo heredado se marca
   como `legacy` y entra por una ruta distinta, no colándose por la ruta normal
   con valores por defecto inventados.

El punto 3 es el que importa a largo plazo: garantiza que ningún modelo **futuro**
pueda activarse sin sus artefactos de calibración. La excepción es el heredado, y
es una excepción con nombre.

## Alternativas consideradas

**Inventar umbrales por defecto y activar las tres compuertas igualmente.**
Descartada, y es la alternativa peligrosa. Un umbral de softmax sin calibrar es
exactamente lo que §7.2 identifica como incorrecto: detecta la duda del modelo, no
su ignorancia. Daría la apariencia de tener detección de desconocidos sin tenerla,
que es peor que no tenerla, porque nadie la volvería a mirar.

**Bloquear el escaneo hasta que exista el modelo nuevo.** Descartada. Deja a los
usuarios actuales con menos función de la que ya tienen. Un refactor que quita
capacidad no se puede publicar.

**Mantener TensorFlow.js en una WebView oculta dentro de la app nativa.**
Descartada: arrastra la WebView, el arranque en frío y el consumo de memoria que
justamente motivan ADR-001, y no resuelve ninguna de las tres carencias.

## Consecuencias

**Positivas**
- La app sigue escaneando desde el primer día del refactor.
- La compuerta de calidad, que es independiente del modelo, empieza a mejorar la
  calidad del dataset de inmediato — lo que acelera el reentrenamiento.
- La arquitectura queda lista: cuando llegue el modelo con doble salida, activar
  las compuertas 2 y 3 es publicar un artefacto, no escribir código.

**Negativas**
- **La tesis central del producto no está entregada hasta la Fase 7.** Cualquier
  material comercial que prometa detección de plaga desconocida antes de eso está
  vendiendo algo que no existe. Que quede escrito aquí.
- Las seis clases actuales sin clase de rechazo seguirán forzando una respuesta
  ante hojas sanas. La compuerta 1 mitiga una parte (rechaza lo que no parece
  hoja) pero no distingue una hoja sana de una enferma.
- Hay dos rutas de activación de modelo —heredada y normal— y las dos hay que
  probarlas.

## Trabajo que esto NO evita

Sigue siendo obligatorio, y es la ruta crítica del producto:

1. **Fase 0:** 300–800 imágenes por clase en condiciones reales del Macizo,
   etiquetadas por agrónomo, con clase de rechazo explícita.
2. **Fase 7:** reentrenamiento con doble salida, calibración por temperatura y
   Mahalanobis, exportación INT8 con verificación de paridad, firma Ed25519.

## Condición de revisión

Este ADR queda **sustituido automáticamente** el día que se publique al canal
`production` un modelo que satisfaga `CalibrationParams.isConsistentWith()`. En
ese momento la ruta heredada se elimina, no se deja «por si acaso».
