package co.agrovision.core.model.zone

import co.agrovision.core.model.scan.RgbImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PROTOTIPO: daño en la hoja (severidad %) estimado por reglas de color sobre la
 * foto del escaneo. Es muy aproximado y NO sustituye a un modelo de segmentación:
 * sirve para ver el flujo y preseleccionar la severidad, que el productor puede
 * cambiar. Todos los umbrales son una primera suposición sin calibrar contra
 * fotos reales; están aquí como constantes con nombre para ajustarlos.
 *
 * Todo es Kotlin puro sobre píxeles ARGB empaquetados ([RgbImage]), sin
 * `android.graphics`, para probarlo en la JVM con imágenes sintéticas.
 *
 * Pasos:
 * 1. Reducir a [MAX_WIDTH] px de ancho (promedio por bloques, misma proporción).
 * 2. HSV por píxel y máscara «verde» (matiz, saturación y valor mínimos).
 * 3. Silueta = verde + cierre morfológico + relleno de huecos, y solo el
 *    componente conexo más grande (suelo, mano y fondo quedan fuera).
 * 4. Daño = píxeles de la silueta que no son verdes y sí son
 *    amarillo/naranja/marrón/rojizo o muy oscuros.
 * 5. % = daño / silueta × 100, redondeado y acotado a 0..100.
 * 6. Si casi no hay verde o la silueta es diminuta: [LeafDamageResult.NotEstimable].
 */
object LeafDamageEstimator {

    /** Ancho máximo de trabajo: suficiente para manchas de roya y barato en un teléfono. */
    const val MAX_WIDTH = 256

    // ── Verde (tejido sano). Matiz en grados 0..360; saturación y valor 0..1. ──
    /** Verde amarillento del haz joven. Por debajo empieza el amarillo (clorosis). */
    const val GREEN_HUE_MIN = 65f
    /** Verde azulado. Más arriba ya es cian/azul (cielo, plásticos). */
    const val GREEN_HUE_MAX = 170f
    /** Por debajo es gris/blanco (brillo especular, cielo nublado): no es verde. */
    const val GREEN_SAT_MIN = 0.18f
    /** Por debajo es sombra profunda: no se puede decir que sea verde. */
    const val GREEN_VAL_MIN = 0.15f

    // ── Daño (dentro de la silueta y no verde). ──
    /** Rojizo/marrón/naranja/amarillo: matiz en [0, DAMAGE_HUE_MAX) … */
    const val DAMAGE_HUE_MAX = 65f
    /** … o desde aquí hasta 360 (marrón rojizo que da la vuelta al círculo). */
    const val DAMAGE_HUE_WRAP_MIN = 330f
    /** Saturación mínima para contar un matiz cálido como lesión (no un gris). */
    const val DAMAGE_SAT_MIN = 0.20f
    /** Valor mínimo del matiz cálido: más oscuro lo cubre [DARK_VAL_MAX]. */
    const val DAMAGE_VAL_MIN = 0.10f
    /** Tejido necrótico negro o muy oscuro, de cualquier matiz. */
    const val DARK_VAL_MAX = 0.18f

    // ── Silueta. ──
    /** Radio del cierre morfológico, como fracción del ancho de trabajo (~5 px a 256). */
    const val CLOSE_RADIUS_FRACTION = 0.02f

    // ── Salvaguardas. ──
    /** Si el verde ocupa menos de esta fracción de la imagen, no hay hoja que medir. */
    const val MIN_GREEN_FRACTION = 0.05f
    /** Si la silueta ocupa menos de esta fracción de la imagen, la hoja está muy lejos. */
    const val MIN_LEAF_FRACTION = 0.15f

    /** Solo el resultado. */
    fun estimate(image: RgbImage): LeafDamageResult = analyze(image).result

    /** Resultado más las máscaras de trabajo (para la vista de depuración). */
    fun analyze(image: RgbImage): LeafDamageAnalysis {
        val small = downscale(image, MAX_WIDTH)
        val w = small.width
        val h = small.height
        val n = w * h
        val green = BooleanArray(n)
        val lesion = BooleanArray(n)
        val hsv = FloatArray(3)
        var greenCount = 0
        for (i in 0 until n) {
            toHsv(small.pixels[i], hsv)
            if (isGreen(hsv)) {
                green[i] = true
                greenCount++
            } else if (isLesionColor(hsv)) {
                lesion[i] = true
            }
        }

        val empty = BooleanArray(n)
        if (greenCount < n * MIN_GREEN_FRACTION) {
            return LeafDamageAnalysis(
                LeafDamageResult.NotEstimable(LeafDamageResult.Reason.TOO_LITTLE_GREEN),
                small, empty, empty,
            )
        }

        val radius = max(1, (w * CLOSE_RADIUS_FRACTION).roundToInt())
        val closed = erode(dilate(green, w, h, radius), w, h, radius)
        val filled = fillHoles(closed, w, h)
        val leaf = largestComponent(filled, w, h)

        var leafCount = 0
        var damageCount = 0
        val damage = BooleanArray(n)
        for (i in 0 until n) {
            if (!leaf[i]) continue
            leafCount++
            if (!green[i] && lesion[i]) {
                damage[i] = true
                damageCount++
            }
        }

        if (leafCount < n * MIN_LEAF_FRACTION) {
            return LeafDamageAnalysis(
                LeafDamageResult.NotEstimable(LeafDamageResult.Reason.LEAF_TOO_SMALL),
                small, leaf, damage,
            )
        }
        return LeafDamageAnalysis(LeafDamageResult.Estimated(damagePercent(damageCount, leafCount)), small, leaf, damage)
    }

    /** daño / silueta × 100, redondeado y acotado a 0..100. Silueta vacía: 0. */
    fun damagePercent(damagePixels: Int, leafPixels: Int): Int {
        if (leafPixels <= 0) return 0
        return (damagePixels * 100.0 / leafPixels).roundToInt().coerceIn(0, 100)
    }

    /** Reduce por promedio de bloques a [maxWidth] de ancho conservando la proporción. */
    fun downscale(image: RgbImage, maxWidth: Int): RgbImage {
        if (image.width <= maxWidth) return image
        val w = maxWidth
        val h = max(1, (image.height.toLong() * maxWidth / image.width).toInt())
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = y * image.height / h
            val y1 = max(y0 + 1, (y + 1) * image.height / h)
            for (x in 0 until w) {
                val x0 = x * image.width / w
                val x1 = max(x0 + 1, (x + 1) * image.width / w)
                var r = 0L
                var g = 0L
                var b = 0L
                for (sy in y0 until y1) {
                    val row = sy * image.width
                    for (sx in x0 until x1) {
                        val p = image.pixels[row + sx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val c = (y1 - y0) * (x1 - x0)
                out[y * w + x] = (0xFF shl 24) or ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (b / c).toInt()
            }
        }
        return RgbImage(w, h, out)
    }

    internal fun isGreen(hsv: FloatArray): Boolean =
        hsv[0] in GREEN_HUE_MIN..GREEN_HUE_MAX && hsv[1] >= GREEN_SAT_MIN && hsv[2] >= GREEN_VAL_MIN

    internal fun isLesionColor(hsv: FloatArray): Boolean {
        if (hsv[2] <= DARK_VAL_MAX) return true
        val warm = hsv[0] < DAMAGE_HUE_MAX || hsv[0] >= DAMAGE_HUE_WRAP_MIN
        return warm && hsv[1] >= DAMAGE_SAT_MIN && hsv[2] >= DAMAGE_VAL_MIN
    }

    /** ARGB a HSV: matiz 0..360, saturación y valor 0..1. */
    internal fun toHsv(argb: Int, out: FloatArray) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val hue = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        out[0] = if (hue < 0f) hue + 360f else hue
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    // ── Morfología con ventana cuadrada separable (O(n·r)). ──

    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int) = boxFilter(src, w, h, r, any = true)
    private fun erode(src: BooleanArray, w: Int, h: Int, r: Int) = boxFilter(src, w, h, r, any = false)

    /** `any = true`: dilatación (algún vecino lleno). `false`: erosión (todos llenos). Los vecinos
     *  fuera de la imagen se ignoran, así la erosión no se come la hoja que toca el borde. */
    private fun boxFilter(src: BooleanArray, w: Int, h: Int, r: Int, any: Boolean): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = !any
                for (dx in -r..r) {
                    val sx = x + dx
                    if (sx !in 0 until w) continue
                    val s = src[y * w + sx]
                    if (any && s) { v = true; break }
                    if (!any && !s) { v = false; break }
                }
                tmp[y * w + x] = v
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = !any
                for (dy in -r..r) {
                    val sy = y + dy
                    if (sy !in 0 until h) continue
                    val s = tmp[sy * w + x]
                    if (any && s) { v = true; break }
                    if (!any && !s) { v = false; break }
                }
                out[y * w + x] = v
            }
        }
        return out
    }

    /** Rellena lo que no está conectado al borde de la imagen por zonas vacías. */
    private fun fillHoles(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun push(i: Int) {
            if (!mask[i] && !outside[i]) {
                outside[i] = true
                stack[top++] = i
            }
        }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }

    /** Componente 4-conexo más grande de [mask]. */
    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var best = 0
        var bestSize = 0
        var next = 0
        for (start in 0 until w * h) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var size = 0
            var top = 0
            label[start] = next
            stack[top++] = start
            while (top > 0) {
                val i = stack[--top]
                size++
                val x = i % w
                val y = i / w
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; stack[top++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; stack[top++] = i + 1 }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; stack[top++] = i - w }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; stack[top++] = i + w }
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        return BooleanArray(w * h) { best != 0 && label[it] == best }
    }
}

/** Resultado del prototipo: un número o el motivo por el que no se da. */
sealed interface LeafDamageResult {
    data class Estimated(val pct: Int) : LeafDamageResult

    data class NotEstimable(val reason: Reason) : LeafDamageResult

    enum class Reason {
        /** Casi no hay verde: no se encontró hoja (o está muy oscura o quemada). */
        TOO_LITTLE_GREEN,

        /** La silueta ocupa muy poco de la foto: hay que acercarse. */
        LEAF_TOO_SMALL,

        /** El análisis falló (no debería pasar); se trata igual que «no estimable». */
        FAILED,
    }
}

/** Resultado más la imagen de trabajo y sus máscaras, para depurar umbrales. */
class LeafDamageAnalysis(
    val result: LeafDamageResult,
    val image: RgbImage,
    val leafMask: BooleanArray,
    val damageMask: BooleanArray,
) {
    /**
     * Imagen de trabajo con las máscaras encima: fuera de la hoja oscurecido,
     * hoja teñida de verde y daño teñido de magenta (contrasta con marrón y verde).
     */
    fun overlay(): RgbImage {
        val px = image.pixels
        val w = image.width
        val h = image.height
        // Contorno del daño: píxel de daño con algún vecino (4-conexo) que no lo es.
        fun isDamage(x: Int, y: Int) = x in 0 until w && y in 0 until h && damageMask[y * w + x]
        val out = IntArray(px.size) { i ->
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val x = i % w
            val y = i / w
            when {
                damageMask[i] && !(isDamage(x - 1, y) && isDamage(x + 1, y) && isDamage(x, y - 1) && isDamage(x, y + 1)) ->
                    rgb(255, 0, 255)
                // Relleno más suave que antes: la textura de la hoja se sigue viendo.
                damageMask[i] -> rgb(blend(r, 255, 0.55), blend(g, 0, 0.55), blend(b, 255, 0.55))
                leafMask[i] -> rgb(blend(r, 0, 0.30), blend(g, 220, 0.30), blend(b, 0, 0.30))
                else -> rgb(r / 5, g / 5, b / 5)
            }
        }
        return RgbImage(w, h, out)
    }

    private fun blend(c: Int, tint: Int, alpha: Double) = (c * (1 - alpha) + tint * alpha).toInt()
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Nivel del selector manual para un % de daño: Poca < 10, Media 10..<30,
 * Alta 30..60, Muy alta > 60. Los límites (10 y 30) caen en el nivel superior.
 */
fun severityBandFor(damagePct: Int): SeverityBand = when {
    damagePct < 10 -> SeverityBand.POCA
    damagePct < 30 -> SeverityBand.MEDIA
    damagePct <= 60 -> SeverityBand.ALTA
    else -> SeverityBand.MUY_ALTA
}

/**
 * Índice (en [ZoneRule.filas], que viene ordenada por `orden`) de la fila que
 * corresponde a [damagePct] solo por severidad: la primera con daño ≤ `sevMax`;
 * si ninguna, la última. `null` si la regla no tiene filas. La incidencia no
 * entra: sigue siendo manual.
 */
fun ZoneRule.rowIndexForSeverity(damagePct: Int): Int? {
    if (filas.isEmpty()) return null
    val i = filas.indexOfFirst { damagePct <= it.sevMax }
    return if (i >= 0) i else filas.lastIndex
}
