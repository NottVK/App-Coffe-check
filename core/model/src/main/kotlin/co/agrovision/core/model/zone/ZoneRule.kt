package co.agrovision.core.model.zone

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Regla de «zona a vigilar» que define un agrónomo por tipo de enfermedad.
 *
 * No es una fórmula científica: traduce el criterio experto de la tabla a un
 * rango de radio alrededor del foco. Mientras [calibrada] sea `false`, todo
 * resultado se presenta como «Estimación sin calibrar»; la calibración llegará
 * con los vuelos de dron multiespectral y la app solo aplica la regla vigente.
 *
 * Es el espejo exacto de `aplicar-regla.ts` en el API: si cambia allí, cambia aquí.
 */
data class ZoneRule(
    val tipoEnfermedad: String,
    val version: Int,
    val calibrada: Boolean,
    val filas: List<ZoneRuleRow>,
)

data class ZoneRuleRow(
    val orden: Int,
    val sevMax: Double,
    val incMax: Double,
    val radioMinM: Double,
    val radioMaxM: Double,
)

data class ZoneEstimate(
    val filaAplicada: ZoneRuleRow,
    val radioMinM: Double,
    val radioMaxM: Double,
    val plantasMin: Int,
    val plantasMax: Int,
    val calibrada: Boolean,
    val reglaVersion: Int,
)

/**
 * Primera fila (por orden) con severidad <= sevMax Y incidencia <= incMax; si
 * ninguna cumple, la última. Devuelve `null` si la regla no tiene filas: sin
 * tabla no se inventa una zona.
 */
fun estimateZone(
    rule: ZoneRule,
    severityPct: Double,
    incidencePct: Double,
    plantsPerHa: Int,
): ZoneEstimate? {
    val ordered = rule.filas.sortedBy { it.orden }
    if (ordered.isEmpty()) return null
    val row = ordered.firstOrNull { severityPct <= it.sevMax && incidencePct <= it.incMax } ?: ordered.last()
    return ZoneEstimate(
        filaAplicada = row,
        radioMinM = row.radioMinM,
        radioMaxM = row.radioMaxM,
        plantasMin = plantsInRadius(row.radioMinM, plantsPerHa),
        plantasMax = plantsInRadius(row.radioMaxM, plantsPerHa),
        calibrada = rule.calibrada,
        reglaVersion = rule.version,
    )
}

/**
 * Plantas dentro de un círculo de radio [radiusM] con una densidad en plantas/ha (1 ha = 10 000 m²).
 *
 * Radio y densidad van acotados por [ZoneRuleLimits.MAX_RADIUS_M] y
 * [ZoneDefaults.MAX_PLANTS_PER_HA]: el peor caso (500 m a 1 000 000 plantas/ha,
 * unos 78,5 millones) cabe holgado en un `Int`. Fuera de esos límites se lanza
 * una excepción en vez de saturar en silencio a `Int.MAX_VALUE`.
 */
fun plantsInRadius(radiusM: Double, plantsPerHa: Int): Int {
    require(radiusM.isFinite() && radiusM in 0.0..ZoneRuleLimits.MAX_RADIUS_M) { "Radio fuera de rango: $radiusM" }
    require(plantsPerHa in ZoneDefaults.MIN_PLANTS_PER_HA..ZoneDefaults.MAX_PLANTS_PER_HA) {
        "Densidad fuera de rango: $plantsPerHa"
    }
    return (PI * radiusM * radiusM * plantsPerHa / SQUARE_METERS_PER_HA).roundToInt()
}

private const val SQUARE_METERS_PER_HA = 10_000.0

/** Límites del contrato de la tabla; espejo de `validar-filas.ts` en el API. */
object ZoneRuleLimits {
    const val MAX_ROWS = 12
    const val MAX_RADIUS_M = 500.0
    const val FULL_COVERAGE_PCT = 100.0
}

/**
 * `true` si la regla cumple el contrato de `validar-filas.ts` y es del tipo [type].
 * Única validación de dominio: la usan la descarga, y la caché al leer y al escribir.
 *
 * - Entre 1 y [ZoneRuleLimits.MAX_ROWS] filas, con `orden` 1..n consecutivo en
 *   la posición de la lista (la lista debe venir ordenada por `orden`).
 * - `sevMax` e `incMax` en (0, 100]; radios en [0, [ZoneRuleLimits.MAX_RADIUS_M]]
 *   y `radioMinM <= radioMaxM`.
 * - Umbrales y radios no decrecientes entre filas: más daño nunca da menos zona.
 * - La última fila cubre 100/100, así que toda observación cae en alguna fila.
 */
fun ZoneRule.isValidFor(type: String): Boolean {
    if (tipoEnfermedad != type || version < 1) return false
    if (filas.isEmpty() || filas.size > ZoneRuleLimits.MAX_ROWS) return false
    filas.forEachIndexed { i, row ->
        if (row.orden != i + 1) return false
        if (!row.sevMax.isPercent() || !row.incMax.isPercent()) return false
        if (!row.radioMinM.isRadius() || !row.radioMaxM.isRadius() || row.radioMinM > row.radioMaxM) return false
        if (i > 0) {
            val prev = filas[i - 1]
            if (row.sevMax < prev.sevMax || row.incMax < prev.incMax) return false
            if (row.radioMinM < prev.radioMinM || row.radioMaxM < prev.radioMaxM) return false
        }
    }
    val last = filas.last()
    return last.sevMax == ZoneRuleLimits.FULL_COVERAGE_PCT && last.incMax == ZoneRuleLimits.FULL_COVERAGE_PCT
}

private fun Double.isPercent() = isFinite() && this > 0.0 && this <= ZoneRuleLimits.FULL_COVERAGE_PCT
private fun Double.isRadius() = isFinite() && this in 0.0..ZoneRuleLimits.MAX_RADIUS_M

/**
 * Tipos de regla que publica el API y su equivalencia con las clases del modelo.
 *
 * Se acepta tanto el `classId` del modelo («Leaf rust») como el `pestId` de la
 * ficha del catálogo («Enfermedad_Roya»): el resultado del escaneo trae los dos,
 * y el `pestId` sobrevive a un reentrenamiento que renombre la clase. Cualquier
 * otra clase —deficiencias, hoja sana, rechazos— no tiene zona.
 */
object ZoneRuleTypes {
    const val ROYA = "roya"
    const val CERCOSPORA = "cercospora"
    const val PHOMA = "phoma"
    const val ANTRACNOSIS = "antracnosis"
    const val MINADOR = "minador"
    const val HORMIGAS = "hormigas"

    val ALL: List<String> = listOf(ROYA, CERCOSPORA, PHOMA, ANTRACNOSIS, MINADOR, HORMIGAS)

    private val byClassId = mapOf(
        "Leaf rust" to ROYA,
        "Cerscospora" to CERCOSPORA,
        "Phoma" to PHOMA,
        "Enfermedad_Antracnosis" to ANTRACNOSIS,
        "Miner" to MINADOR,
        "Plaga_Hormigas" to HORMIGAS,
    )

    private val byPestId = mapOf(
        "Enfermedad_Roya" to ROYA,
        "Enfermedad_ManchadeHierro" to CERCOSPORA,
        "Enfermedad_Phoma" to PHOMA,
        "Enfermedad_Antracnosis" to ANTRACNOSIS,
        "Plaga_Minador" to MINADOR,
        "Plaga_Hormigas" to HORMIGAS,
    )

    fun forClass(classId: String?, pestId: String? = null): String? =
        classId?.let(byClassId::get) ?: pestId?.let(byPestId::get)
}

/** Valores del selector manual de severidad: un representativo por rango. */
enum class SeverityBand(val representativePct: Double) {
    POCA(5.0),
    MEDIA(20.0),
    ALTA(45.0),
    MUY_ALTA(75.0),
}

object ZoneDefaults {
    const val PLANTS_PER_HA = 5_000
    const val MIN_PLANTS_PER_HA = 1
    const val MAX_PLANTS_PER_HA = 1_000_000

    /** Densidad escrita por el productor, o `null` si no es un entero en rango. */
    fun parsePlantsPerHa(text: String): Int? =
        text.toIntOrNull()?.takeIf { it in MIN_PLANTS_PER_HA..MAX_PLANTS_PER_HA }
}
