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

/** Plantas dentro de un círculo de radio [radiusM] con una densidad en plantas/ha (1 ha = 10 000 m²). */
fun plantsInRadius(radiusM: Double, plantsPerHa: Int): Int =
    (PI * radiusM * radiusM * plantsPerHa / SQUARE_METERS_PER_HA).roundToInt()

private const val SQUARE_METERS_PER_HA = 10_000.0

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
}
