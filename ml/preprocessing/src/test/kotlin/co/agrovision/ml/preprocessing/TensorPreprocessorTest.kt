package co.agrovision.ml.preprocessing

import co.agrovision.core.model.scan.RgbImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TensorPreprocessorTest {

    private val preprocessor = TensorPreprocessor()
    private val pixel = RgbImage(1, 1, intArrayOf((0xFF shl 24) or (255 shl 16) or (128 shl 8) or 0))

    @Test
    fun `UINT8 recibe los bytes crudos`() {
        val buffer = preprocessor.toInputBuffer(pixel, quantized = true)

        assertEquals(listOf(255, 128, 0), List(3) { buffer.get(it).toInt() and 0xFF })
    }

    @Test
    fun `INT8 desplaza en 128 y conserva el orden de los valores`() {
        val buffer = preprocessor.toInputBuffer(pixel, quantized = true, signed = true)

        assertEquals(listOf(127, 0, -128), List(3) { buffer.get(it).toInt() })
    }
}
