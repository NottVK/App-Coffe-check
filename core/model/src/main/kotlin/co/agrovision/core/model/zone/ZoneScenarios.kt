package co.agrovision.core.model.zone

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Un escenario de la tabla de la regla, ya formateado para mostrarlo tal cual:
 * «Hasta [incidenciaPct] % de plantas afectadas y [severidadPct] % de daño en
 * hoja: [radioMin] a [radioMax] m». Los textos fijos van en `strings.xml`; aquí
 * solo los números, para poder probarlos sin Android.
 */
data class ZoneScenario(
    val kind: Kind,
    val incidenciaPct: String,
    val severidadPct: String,
    val radioMin: String,
    val radioMax: String,
) {
    enum class Kind {
        /** «Hasta X % … y Y % …». */
        HASTA,

        /** Última fila de una tabla con varias: «Más de lo anterior». */
        MAS,

        /** Tabla de una sola fila: aplica en cualquier caso. */
        CUALQUIERA,
    }
}

/**
 * Las filas de la regla como escenarios, en orden. Lista vacía si la regla no
 * es válida para [type] (misma validación que la caché, [isValidFor]): con una
 * tabla rota no se muestra nada antes que algo inventado.
 */
fun ZoneRule.scenarios(type: String): List<ZoneScenario> {
    if (!isValidFor(type)) return emptyList()
    val last = filas.lastIndex
    return filas.mapIndexed { i, row ->
        ZoneScenario(
            kind = when {
                last == 0 -> ZoneScenario.Kind.CUALQUIERA
                i == last -> ZoneScenario.Kind.MAS
                else -> ZoneScenario.Kind.HASTA
            },
            incidenciaPct = formatZoneNumber(row.incMax),
            severidadPct = formatZoneNumber(row.sevMax),
            radioMin = formatZoneNumber(row.radioMinM),
            radioMax = formatZoneNumber(row.radioMaxM),
        )
    }
}

/**
 * Con daño estimado, solo la fila que corresponde por severidad; sin estimación
 * (incluido `null` mientras se calcula), todas. Conserva el tipo y los números
 * de la fila original; la incidencia sigue siendo manual.
 */
fun ZoneRule.visibleScenarios(type: String, damage: LeafDamageResult?): List<ZoneScenario> {
    val scenarios = scenarios(type)
    if (scenarios.isEmpty() || damage !is LeafDamageResult.Estimated) return scenarios
    val index = rowIndexForSeverity(damage.pct) ?: return emptyList()
    return listOf(scenarios[index])
}

/** «15» en vez de «15.0»; «2,5» con coma decimal, como se escribe en Colombia. */
fun formatZoneNumber(value: Double): String =
    DecimalFormat("0.#", DecimalFormatSymbols(Locale.forLanguageTag("es-CO"))).format(value)
