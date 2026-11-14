package co.agrovision.core.model.zone

import co.agrovision.core.model.scan.RgbImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Imágenes sintéticas: hoja elíptica verde sobre un fondo, con o sin mancha. */
class LeafDamageTest {

    private val leafGreen = argb(60, 140, 50)
    private val brown = argb(120, 75, 30)
    private val grayBackground = argb(200, 200, 200)
    private val soilBrown = argb(110, 70, 35)

    @Test
    fun `hoja verde sana da cerca de 0`() {
        val result = LeafDamageEstimator.estimate(leaf(512, 384, background = grayBackground))
        val pct = assertInstanceOf(LeafDamageResult.Estimated::class.java, result).pct
        assertTrue(pct <= 1, "daño $pct")
    }

    @Test
    fun `hoja con 30 por ciento de zona marron da cerca de 30`() {
        val image = leaf(512, 384, background = grayBackground, damageFraction = 0.30)
        val pct = (LeafDamageEstimator.estimate(image) as LeafDamageResult.Estimated).pct
        assertTrue(pct in 25..35, "daño $pct")
    }

    @Test
    fun `el fondo marron fuera de la hoja no cuenta como dano`() {
        val result = LeafDamageEstimator.estimate(leaf(512, 384, background = soilBrown))
        val pct = assertInstanceOf(LeafDamageResult.Estimated::class.java, result).pct
        assertTrue(pct <= 1, "daño $pct")
    }

    @Test
    fun `imagen sin verde no es estimable`() {
        val image = RgbImage(300, 200, IntArray(300 * 200) { soilBrown })
        assertEquals(
            LeafDamageResult.NotEstimable(LeafDamageResult.Reason.TOO_LITTLE_GREEN),
            LeafDamageEstimator.estimate(image),
        )
    }

    @Test
    fun `hoja diminuta no es estimable`() {
        // Elipse que ocupa ~9 % de la imagen: hay verde, pero la hoja está muy lejos.
        val image = leaf(512, 384, background = grayBackground, scale = 0.34)
        assertEquals(
            LeafDamageResult.NotEstimable(LeafDamageResult.Reason.LEAF_TOO_SMALL),
            LeafDamageEstimator.estimate(image),
        )
    }

    @Test
    fun `porcentaje acotado entre 0 y 100`() {
        assertEquals(0, LeafDamageEstimator.damagePercent(0, 1000))
        assertEquals(100, LeafDamageEstimator.damagePercent(1000, 1000))
        assertEquals(100, LeafDamageEstimator.damagePercent(1500, 1000))
        assertEquals(0, LeafDamageEstimator.damagePercent(-5, 1000))
        assertEquals(0, LeafDamageEstimator.damagePercent(10, 0))
    }

    @Test
    fun `hoja casi toda danada se queda en 100 o menos`() {
        val image = leaf(512, 384, background = grayBackground, damageFraction = 0.85)
        val pct = (LeafDamageEstimator.estimate(image) as LeafDamageResult.Estimated).pct
        assertTrue(pct in 80..100, "daño $pct")
    }

    @Test
    fun `reduce a 256 de ancho conservando la proporcion`() {
        val small = LeafDamageEstimator.downscale(RgbImage(1024, 768, IntArray(1024 * 768) { leafGreen }), 256)
        assertEquals(256, small.width)
        assertEquals(192, small.height)
        assertEquals(leafGreen, small.pixels[0])
    }

    @Test
    fun `la mascara de depuracion tiene el tamano de trabajo`() {
        val analysis = LeafDamageEstimator.analyze(leaf(512, 384, background = grayBackground, damageFraction = 0.3))
        val overlay = analysis.overlay()
        assertEquals(256, overlay.width)
        assertEquals(192, overlay.height)
        assertTrue(analysis.damageMask.any { it })
    }

    @Test
    fun `nivel del selector segun el porcentaje`() {
        assertEquals(SeverityBand.POCA, severityBandFor(0))
        assertEquals(SeverityBand.POCA, severityBandFor(9))
        assertEquals(SeverityBand.MEDIA, severityBandFor(10))
        assertEquals(SeverityBand.MEDIA, severityBandFor(29))
        assertEquals(SeverityBand.ALTA, severityBandFor(30))
        assertEquals(SeverityBand.ALTA, severityBandFor(60))
        assertEquals(SeverityBand.MUY_ALTA, severityBandFor(61))
        assertEquals(SeverityBand.MUY_ALTA, severityBandFor(100))
    }

    @Test
    fun `fila resaltada por sevMax`() {
        val rule = ZoneRule(
            tipoEnfermedad = "roya",
            version = 1,
            calibrada = false,
            filas = listOf(
                ZoneRuleRow(1, sevMax = 10.0, incMax = 20.0, radioMinM = 0.0, radioMaxM = 3.0),
                ZoneRuleRow(2, sevMax = 30.0, incMax = 40.0, radioMinM = 3.0, radioMaxM = 8.0),
                ZoneRuleRow(3, sevMax = 60.0, incMax = 70.0, radioMinM = 8.0, radioMaxM = 15.0),
                ZoneRuleRow(4, sevMax = 100.0, incMax = 100.0, radioMinM = 15.0, radioMaxM = 30.0),
            ),
        )
        assertEquals(0, rule.rowIndexForSeverity(0))
        assertEquals(0, rule.rowIndexForSeverity(10))
        assertEquals(1, rule.rowIndexForSeverity(11))
        assertEquals(1, rule.rowIndexForSeverity(30))
        assertEquals(2, rule.rowIndexForSeverity(45))
        assertEquals(3, rule.rowIndexForSeverity(100))
        assertEquals(null, rule.copy(filas = emptyList()).rowIndexForSeverity(20))
    }

    /**
     * Elipse centrada que ocupa [scale] de cada semieje (con 0.9 cubre ~64 % de la
     * imagen). Con [damageFraction] > 0, una elipse concéntrica (dentro de la hoja,
     * sin tocar su borde) es marrón y ocupa esa fracción del área de la hoja.
     */
    private fun leaf(
        w: Int,
        h: Int,
        background: Int,
        scale: Double = 0.9,
        damageFraction: Double = 0.0,
    ): RgbImage {
        val cx = w / 2.0
        val cy = h / 2.0
        val ax = w / 2.0 * scale
        val ay = h / 2.0 * scale
        fun inside(x: Int, y: Int): Boolean {
            val dx = (x + 0.5 - cx) / ax
            val dy = (y + 0.5 - cy) / ay
            return dx * dx + dy * dy <= 1.0
        }
        // Mancha: elipse concéntrica más pequeña con área = damageFraction de la hoja.
        val k = kotlin.math.sqrt(damageFraction)
        fun inLesion(x: Int, y: Int): Boolean {
            if (k == 0.0) return false
            val dx = (x + 0.5 - cx) / (ax * k)
            val dy = (y + 0.5 - cy) / (ay * k)
            return dx * dx + dy * dy <= 1.0
        }
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                inLesion(x, y) -> brown
                inside(x, y) -> leafGreen
                else -> background
            }
        }
        return RgbImage(w, h, px)
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
