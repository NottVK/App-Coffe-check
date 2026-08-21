# ADR-016 · Modelo propio entrenado sobre JMuBEN, no Teachable Machine

- **Estado:** aceptado
- **Fecha:** 2026-08-14
- **Sustituye parcialmente a:** [ADR-014](0014-modelo-heredado-sin-ood.md)
- **Autor de la estructura:** Diego Fernando Calderón Silva, arquitecto de software

## Contexto

La app publicada usa un clasificador de Teachable Machine con seis clases,
exportado en formato TensorFlow.js. ADR-014 dejó constancia de su limitación
central: **exporta solo logits**. Sin cabeza de embedding no existen ni el
detector de fuera de distribución (§13.2, compuerta 2) ni la agrupación por
similitud del servidor (§13.4). Es el error prohibido nº 3 del `CLAUDE.md`.

La consecuencia práctica no es sutil. Ese modelo no sabe reconocer cuándo está
viendo algo que nunca vio: ante una hoja de plátano responde «roya» con confianza
alta, y la app no tiene forma de saber que se está equivocando.

Además no había dataset propio. La Fase 0 —recolectar 300–800 imágenes por clase
en campo— es de tres a cinco semanas de trabajo que aún no ha empezado.

## Decisión

**Entrenar un modelo propio de doble salida sobre JMuBEN + JMuBEN2, con un tronco
preentrenado en ImageNet.**

### Datos

JMuBEN y JMuBEN2 (Jepkoech, Kenduiywo, Mugo y Chebet, 2021 · Data in Brief 36,
107142 · **CC BY 4.0**): 58 555 imágenes de café arábica recogidas en la
plantación Mutira, condado de Kirinyaga, Kenia, en condiciones reales de campo y
con acompañamiento de un patólogo.

| Clase JMuBEN | Clase del modelo | Ficha del catálogo |
|---|---|---|
| Leaf rust | `Enfermedad_Roya` | ya existía |
| Cerscospora | `Enfermedad_ManchadeHierro` | ya existía |
| Phoma | `Enfermedad_Phoma` | **nueva** |
| Miner | `Plaga_Minador` | **nueva** |
| Healthy | `Sana` | sin ficha (`pest_id: null`) |

`Ojo de Gallo`, `Antracnosis` y las dos deficiencias nutricionales **quedan fuera
del modelo**: no hay datos públicos. Siguen en el catálogo como fichas de
consulta. Es la opción honesta — mejor que el modelo no las nombre a que las
confunda con lo que sí conoce.

### Arquitectura

Tronco `MobileNetV3Large` preentrenado con ImageNet, congelado en la primera
etapa y con las 60 capas superiores descongeladas en la segunda. Encima:

```
GAP → Dropout → Dense(128) ─┬─→ embedding   (salida 1)
                            └─→ Dense(5) → logits (salida 2)
```

No se entrena desde cero: se hereda un extractor de características que costó
1,28 millones de imágenes y solo se le enseña café encima. Es lo que permite
tener un modelo utilizable en cuarenta minutos de GPU en lugar de días.

### Framework: Keras/TensorFlow, no PyTorch

El `CLAUDE.md` §5 dice «Python 3.11 + PyTorch + TFLite». **Se desvía a Keras.**

La razón es la conversión. PyTorch → TFLite exige pasar por ONNX o por
`ai-edge-torch`, un paso frágil que además complica la cuantización entera
completa con dataset representativo. Con Keras el converter es nativo y de una
línea.

Y hay un argumento de segundo orden que pesa más: §18.4 exige verificar la
paridad entre el modelo entrenado y el exportado. Con una conversión entre
frameworks, esa verificación tiene que atrapar errores de traducción *además* de
errores de cuantización. Sin conversión entre frameworks, solo mide lo que
interesa medir.

## Consecuencias

### A favor

- **El detector OOD empieza a existir.** Es el cambio de fondo: la app pasa de
  adivinar siempre a poder decir «no la reconozco» y derivar al agrónomo.
- **Tres clases nuevas**, incluida `Sana`. Hoy la app no puede decirle a nadie
  que su hoja está bien.
- **Reentrenar cuesta un comando.** Al llegar fotos del piloto se colocan en la
  carpeta de su clase y se vuelve a ejecutar.
- **El reparto es estable.** Se deriva del hash del nombre del archivo, no de un
  barajado: una imagen que estaba en prueba sigue en prueba aunque el dataset
  crezca, y las métricas entre versiones son comparables.
- **Trazabilidad completa.** `metrics.json` lleva la cita del dataset, la
  licencia, la semilla y el entorno.

### En contra, y hay que decirlas

**Brecha de dominio por encuadre.** JMuBEN se publica **recortado a 128×128
pegado a la lesión**. En campo el teléfono manda un recorte central de una foto
de 1280 px, donde la lesión ocupa una fracción del cuadro. Se mitiga con
aumentación de zoom hasta el 35 %, pero es mitigación, no cura.

**Brecha geográfica.** Kirinyaga (Kenia) no es el Macizo Colombiano. Otra altura,
otro suelo, otras variedades, otra luz. Las mismas especies de hongo se ven
distintas sobre otra variedad de café.

**El AUROC no se puede medir todavía.** Hace falta un conjunto de imágenes fuera
de dominio. Sin él, `metrics.json` registra `null` y el pipeline **impide
promover a `production`**: el modelo puede ir a `draft` o `internal`, nada más.
Las mejores muestras fuera de dominio llegarán del propio piloto — son justo las
que la app manda a revisión.

**Las fichas nuevas no llevan recomendaciones.** `recommendations: []` a
propósito, hasta que un ingeniero agrónomo con tarjeta profesional vigente las
avale (§13.6). Publicar dosis sin respaldo expone ante el ICA.

### Neutras

- Embedding de 128 dimensiones en lugar de 256: reduce `calibration.json` de 1 MB
  a 276 KB de descarga y cuadruplica la velocidad de Mahalanobis en el teléfono.
  Con cinco clases no hace falta más.
- El modelo de Teachable Machine deja de usarse. ADR-014 queda como registro
  histórico de por qué no servía.

## Condición de revisión

Este modelo es **un punto de partida, no el destino**. Se revisa cuando ocurra lo
primero de:

1. Haya 300 imágenes de campo por clase tomadas en Huila con teléfonos reales.
2. La exactitud de campo (§19.2) diverja más de 10 puntos de la de validación —
   señal de desplazamiento de dominio.
3. La tasa de no reconocimiento supere el 25 % de forma sostenida.

## Alternativas descartadas

**Seguir con Teachable Machine.** Sin embedding, dos subsistemas completos quedan
muertos. Es el error prohibido nº 3.

**Empotrar un modelo grande ya entrenado (SCOLD, 186 000 imágenes, 97 clases).**
No cabe: cientos de megas y necesita GPU, contra un presupuesto de 4–9 MB y 900 ms
en teléfonos de gama baja sin conexión. Su conocimiento sí se hereda, pero por la
vía del tronco preentrenado, no empotrando el modelo.

**Esperar a tener dataset propio.** Tres a cinco semanas sin nada que enseñar ni
con qué probar el ciclo completo. Con JMuBEN el ciclo se cierra ya y el dataset
propio entra después como mejora incremental, no como bloqueo.

**Detección con cajas (YOLO) en vez de clasificación.** Multiplica de tres a
cinco veces el costo de etiquetado, y el productor fotografía la lesión de cerca:
no hay varias plagas que separar en el cuadro. Coincide con §13.1.
