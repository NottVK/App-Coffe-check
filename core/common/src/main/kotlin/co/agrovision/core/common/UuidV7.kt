package co.agrovision.core.common

import java.security.SecureRandom
import java.util.UUID

/**
 * Generador de UUIDv7 (RFC 9562).
 *
 * Se eligió v7 y no v4 por dos motivos concretos de este producto:
 *
 *  1. **Ordenable por tiempo.** Los 48 bits altos son la marca temporal en
 *     milisegundos. El historial local se ordena por clave primaria sin índice
 *     adicional, y la cola de subida respeta el orden de captura de forma
 *     natural.
 *  2. **Idempotencia.** El identificador se genera en el dispositivo antes de
 *     cualquier operación de red y viaja como `Idempotency-Key` (§9.4). Un
 *     reintento tras una respuesta perdida no duplica la fila en el servidor.
 *
 * Diseño:
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * ├───────────────── unix_ts_ms (48 bits) ─────────────────┼ ver ┼
 * ├ rand_a ┼var┼──────────── rand_b (62 bits) ─────────────────────┤
 * ```
 */
object UuidV7 {

    private val random = SecureRandom()

    /** Monotonía dentro del mismo milisegundo: sin esto, dos capturas en ráfaga
     *  pueden quedar desordenadas en el historial. */
    private var lastTimestampMs = Long.MIN_VALUE
    private var sequence = 0
    private val lock = Any()

    private const val VERSION_7 = 0x7000L

    /**
     * Variante RFC 9562: los dos bits más significativos de la mitad baja valen
     * `10`. Se expresa como `Long.MIN_VALUE` porque es exactamente
     * `0x8000_0000_0000_0000` y sí es constante en tiempo de compilación — un
     * `0x8000000000000000UL.toLong()` no lo es.
     */
    private const val VARIANT_RFC = Long.MIN_VALUE

    private const val MAX_SEQUENCE = 0x0FFF

    fun generate(epochMillis: Long): UUID = synchronized(lock) {
        if (epochMillis == lastTimestampMs) {
            sequence = (sequence + 1) and MAX_SEQUENCE
        } else {
            lastTimestampMs = epochMillis
            sequence = random.nextInt(MAX_SEQUENCE + 1)
        }

        val timestamp = (epochMillis and 0x0000_FFFF_FFFF_FFFFL) shl 16
        val mostSignificant = timestamp or VERSION_7 or sequence.toLong()

        val leastSignificant = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or VARIANT_RFC

        UUID(mostSignificant, leastSignificant)
    }

    fun generateString(epochMillis: Long): String = generate(epochMillis).toString()

    /** Extrae la marca temporal de un UUIDv7. Útil en diagnóstico y en la purga. */
    fun timestampOf(uuid: UUID): Long? =
        if (uuid.version() == 7) uuid.mostSignificantBits ushr 16 else null
}
