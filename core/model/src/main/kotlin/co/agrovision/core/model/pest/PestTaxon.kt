package co.agrovision.core.model.pest

/**
 * Ficha de una plaga en el catálogo local.
 *
 * El catálogo vive completo en el dispositivo y se sincroniza de forma
 * incremental. No es una caché de la API: es la fuente que consulta el
 * productor en el lote, donde no hay señal (§3.1 RF-08).
 */
data class PestTaxon(
    val id: String,
    val scientificName: String,
    val commonName: String,
    /**
     * Nombres regionales. En el Macizo Colombiano una misma plaga tiene tres o
     * cuatro nombres según la vereda, y el productor busca por el que conoce,
     * no por el binomio latino (§2.1).
     */
    val localSynonyms: List<String>,
    val crop: CropType,
    val affectedOrgans: List<PlantOrgan>,
    val description: String,
    val recommendations: List<ManagementRecommendation>,
    val imageRefs: List<String>,
    val version: Int,
    /**
     * Distingue lo que el productor ve como problemas distintos: un hongo se
     * trata, una deficiencia se corrige con fertilización. Mostrarlos con la
     * misma etiqueta llevaría a fumigar una carencia de potasio.
     */
    val kind: PestKind,
    /** Fuente bibliográfica de la ficha. Se muestra al pie: es lo que permite
     *  que un técnico verifique de dónde sale la información. */
    val reference: String?,
    /**
     * Trazabilidad profesional (§13.6). Una ficha con recomendaciones de manejo
     * fitosanitario sin agrónomo con tarjeta vigente detrás no se muestra: no es
     * una formalidad administrativa, es exposición legal ante el ICA.
     */
    val approval: ProfessionalApproval?,
) {
    /** Las recomendaciones solo se muestran si están avaladas. Sin aval, la
     *  ficha se muestra sin la sección de manejo. */
    val showsRecommendations: Boolean
        get() = approval != null && recommendations.isNotEmpty()

    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return commonName.lowercase().contains(needle) ||
            scientificName.lowercase().contains(needle) ||
            localSynonyms.any { it.lowercase().contains(needle) }
    }
}

data class ProfessionalApproval(
    val agronomistName: String,
    val licenseNumber: String,
    val approvedAtEpochMillis: Long,
)

/**
 * Una recomendación de manejo. Se separa del texto libre porque dosis,
 * ingrediente activo y período de carencia son datos con consecuencias:
 * mostrarlos mal no es una errata, es una intoxicación o un lote rechazado
 * en exportación.
 */
data class ManagementRecommendation(
    val kind: ManagementKind,
    val summary: String,
    val detail: String,
    val activeIngredient: String? = null,
    val dosage: String? = null,
    val preHarvestIntervalDays: Int? = null,
)

enum class ManagementKind {
    CULTURAL,
    BIOLOGICO,
    QUIMICO,
    MONITOREO,
}

/**
 * Naturaleza del problema.
 *
 * No es taxonomía: es lo que cambia la acción del productor. Ante un hongo se
 * aplica manejo fitosanitario; ante una deficiencia, fertilización. Y la clase
 * de rechazo del modelo cae en [SANO], que es un resultado de pleno derecho.
 */
enum class PestKind {
    ENFERMEDAD,
    PLAGA,
    DEFICIENCIA,
    SANO,
    OTRO;

    companion object {
        fun parse(raw: String?): PestKind = when {
            raw == null -> OTRO
            raw.contains("enferm", true) -> ENFERMEDAD
            raw.contains("plaga", true) || raw.contains("insect", true) -> PLAGA
            raw.contains("deficien", true) || raw.contains("nutric", true) -> DEFICIENCIA
            raw.contains("sano", true) -> SANO
            else -> OTRO
        }
    }
}

enum class CropType(val displayKey: String) {
    CAFE("cafe"),
    PLATANO("platano"),
    AGUACATE("aguacate"),
    CACAO("cacao"),
    OTRO("otro"),
}

enum class PlantOrgan(val displayKey: String) {
    HOJA("hoja"),
    FRUTO("fruto"),
    TALLO("tallo"),
    RAIZ("raiz"),
    FLOR("flor"),
}

/**
 * Escala de severidad de cinco pasos, la misma que usa la fitopatología del
 * café. La rampa de color va de verde liquen a herrumbre: el color real que
 * toma una hoja conforme avanza la roya (§11.2).
 */
enum class SeverityLevel(val step: Int) {
    SANO(0),
    INCIPIENTE(1),
    MODERADO(2),
    ALTO(3),
    SEVERO(4);

    companion object {
        fun ofStep(step: Int): SeverityLevel =
            entries.firstOrNull { it.step == step } ?: SANO
    }
}
