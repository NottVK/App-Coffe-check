package co.agrovision.ml.registry

import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.ModelLabel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Formato en disco de los artefactos que produce el pipeline de entrenamiento
 * (§13.4). Cinco archivos que viajan y se versionan como **una unidad
 * indivisible**.
 *
 * Estas clases son la frontera con un sistema externo —el pipeline de Python—,
 * así que se mapean explícitamente al dominio en vez de exponerse hacia arriba.
 * Si el formato del pipeline cambia, cambia este archivo y nada más.
 */

@Serializable
internal data class LabelsJson(
    val version: String,
    @SerialName("class_count") val classCount: Int,
    @SerialName("embedding_dim") val embeddingDim: Int,
    val classes: List<LabelEntry>,
) {
    @Serializable
    data class LabelEntry(
        val index: Int,
        @SerialName("class_id") val classId: String,
        @SerialName("display_name") val displayName: String,
        /** `null` en la clase de rechazo, que no corresponde a ninguna plaga. */
        @SerialName("pest_id") val pestId: String? = null,
    )

    fun toDomain(): List<ModelLabel> = classes
        .sortedBy { it.index }
        .map { ModelLabel(it.index, it.classId, it.displayName, it.pestId) }

    /**
     * El orden canónico importa: los logits salen indexados por posición, y una
     * lista con huecos o duplicados haría que el modelo dijera «roya» donde el
     * grafo dice «minador». Sin ningún error visible.
     */
    fun isWellFormed(): Boolean =
        classes.size == classCount &&
            classes.map { it.index }.sorted() == (0 until classCount).toList() &&
            classes.map { it.classId }.toSet().size == classCount
}

@Serializable
internal data class CalibrationJson(
    val version: String,
    val temperature: Float,
    @SerialName("confidence_threshold") val confidenceThreshold: Float,
    @SerialName("margin_threshold") val marginThreshold: Float,
    @SerialName("ood_threshold") val oodThreshold: Float,
    @SerialName("energy_weight") val energyWeight: Float,
    @SerialName("energy_mean") val energyMean: Float,
    @SerialName("energy_std_dev") val energyStdDev: Float,
    /** Un centroide por clase, en el orden de `labels.json`. */
    @SerialName("class_centroids") val classCentroids: List<List<Float>>,
    /** Inversa de la covarianza compartida, aplanada por filas (d×d). */
    @SerialName("precision_matrix") val precisionMatrix: List<Float>,
) {
    fun toDomain() = CalibrationParams(
        temperature = temperature,
        confidenceThreshold = confidenceThreshold,
        marginThreshold = marginThreshold,
        oodThreshold = oodThreshold,
        energyWeight = energyWeight,
        classCentroids = classCentroids.map { it.toFloatArray() },
        precisionMatrix = precisionMatrix.toFloatArray(),
        energyMean = energyMean,
        energyStdDev = energyStdDev,
    )
}

/** Manifiesto que se escribe en la ranura al instalar, para poder reconstruir el
 *  estado tras un reinicio sin volver a consultar al servidor. */
@Serializable
internal data class SlotManifestJson(
    val version: String,
    val channel: String,
    val sha256: String,
    @SerialName("class_count") val classCount: Int,
    @SerialName("embedding_dim") val embeddingDim: Int,
    @SerialName("installed_at") val installedAt: Long,
    /** `true` solo para el modelo portado desde Teachable Machine (ADR-014). */
    @SerialName("is_legacy") val isLegacy: Boolean = false,
)
