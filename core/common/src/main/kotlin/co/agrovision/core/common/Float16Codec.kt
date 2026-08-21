package co.agrovision.core.common

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compresión del embedding a float16 (§9.2).
 *
 * Un embedding de 256 dimensiones ocupa 1 KB en float32 y 512 B en float16. Con
 * una captura por escaneo y varios escaneos diarios durante meses, esa mitad se
 * nota en un teléfono de 32 GB casi lleno — que es el caso común, no el
 * excepcional.
 *
 * La pérdida de precisión es irrelevante para lo que el embedding hace: la
 * distancia de Mahalanobis y la búsqueda de vecinos más cercanos operan sobre
 * magnitudes muy por encima del épsilon de float16.
 *
 * Se implementa a mano en vez de usar `android.util.Half` porque este módulo es
 * Kotlin puro y se prueba en la JVM.
 */
object Float16Codec {

    fun encode(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(toHalf(it)) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Short.SIZE_BYTES == 0) {
            "Un buffer float16 debe tener un número par de bytes, llegaron ${bytes.size}"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Short.SIZE_BYTES) { fromHalf(buffer.getShort()) }
    }

    /** IEEE 754 binary16, con redondeo al par más cercano. */
    private fun toHalf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and SIGN_MASK_16
        var exponent = (bits ushr 23) and 0xFF
        var mantissa = bits and 0x007FFFFF

        // NaN e infinito se propagan tal cual.
        if (exponent == 0xFF) {
            return (sign or 0x7C00 or if (mantissa != 0) 0x0200 else 0).toShort()
        }

        exponent = exponent - 127 + 15

        return when {
            // Desbordamiento por arriba → infinito con signo.
            exponent >= 0x1F -> (sign or 0x7C00).toShort()

            // Subnormal o cero.
            exponent <= 0 -> {
                if (exponent < -10) return sign.toShort()
                mantissa = mantissa or 0x00800000
                val shift = 14 - exponent
                val half = mantissa ushr shift
                val round = if ((mantissa ushr (shift - 1)) and 1 == 1) 1 else 0
                (sign or (half + round)).toShort()
            }

            else -> {
                val half = (exponent shl 10) or (mantissa ushr 13)
                val round = if ((mantissa ushr 12) and 1 == 1) 1 else 0
                (sign or (half + round)).toShort()
            }
        }
    }

    private fun fromHalf(half: Short): Float {
        val bits = half.toInt() and 0xFFFF
        val sign = (bits and SIGN_MASK_16) shl 16
        val exponent = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x03FF

        return when {
            exponent == 0 -> {
                if (mantissa == 0) {
                    java.lang.Float.intBitsToFloat(sign)
                } else {
                    // Subnormal: se normaliza desplazando hasta recuperar el bit
                    // implícito.
                    var e = -1
                    var m = mantissa
                    do {
                        e++
                        m = m shl 1
                    } while (m and 0x0400 == 0)
                    val exp = (127 - 15 - e) shl 23
                    val man = (m and 0x03FF) shl 13
                    java.lang.Float.intBitsToFloat(sign or exp or man)
                }
            }

            exponent == 0x1F -> java.lang.Float.intBitsToFloat(sign or 0x7F800000 or (mantissa shl 13))

            else -> java.lang.Float.intBitsToFloat(
                sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            )
        }
    }

    private const val SIGN_MASK_16 = 0x8000
}
