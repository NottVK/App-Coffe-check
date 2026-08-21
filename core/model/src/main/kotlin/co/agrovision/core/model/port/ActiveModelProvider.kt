package co.agrovision.core.model.port

import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.ModelLabel
import java.io.File
import java.nio.ByteBuffer

/**
 * Entrega el modelo de la ranura activa al subsistema de inferencia.
 *
 * Existe para cortar un ciclo: `:ml:inference` necesita el archivo, `:ml:registry`
 * necesita ejecutar una inferencia de humo antes de activar una ranura. Con este
 * puerto en el dominio, `:ml:registry` depende de `:ml:inference` y nadie depende
 * de nadie hacia atrás.
 */
fun interface ActiveModelProvider {
    /** `null` si no hay ninguna ranura con un modelo válido instalado. */
    suspend fun activeModel(): LoadedModel?
}

class LoadedModel(
    val version: String,
    val labels: List<ModelLabel>,
    /**
     * `null` **solo** en el modelo heredado (ADR-014).
     *
     * Sin calibración no pueden existir las compuertas 2 y 3, y la interfaz
     * tiene que decirlo. Cualquier modelo publicado por la ruta normal la
     * trae: `installToStandby` rechaza el artefacto si falta.
     */
    val calibration: CalibrationParams?,
    val source: ModelSource,
    val embeddingDim: Int,
) {
    val supportsUncertaintyGates: Boolean get() = calibration != null
}

/**
 * De dónde salen los bytes del modelo. Dos formas, y la elección tiene
 * consecuencias reales.
 */
sealed interface ModelSource {

    /**
     * Archivo mapeado en memoria (§7.3). Reduce la presión sobre el montículo de
     * la aplicación, que en un dispositivo de 2 GB es la diferencia entre
     * funcionar y morir por memoria.
     *
     * ⚠️ Mientras el intérprete viva, este archivo **no se puede sobrescribir**.
     * Hacerlo produce un fallo de segmentación en código nativo, sin traza
     * aprovechable desde Kotlin (§23, error nº 11). El esquema de ranuras A/B
     * existe precisamente para que esto no pueda ocurrir por construcción.
     */
    @JvmInline
    value class MappedFile(val file: File) : ModelSource

    /**
     * Bytes ya en memoria. Es la forma que exige el cifrado en reposo (§15.4):
     * el modelo se descifra con la clave del Android Keystore y nunca toca el
     * disco en claro.
     *
     * Cuesta el tamaño del modelo en heap —entre 4 y 9 MB— así que se usa solo
     * cuando el cifrado está activo.
     */
    @JvmInline
    value class InMemory(val buffer: ByteBuffer) : ModelSource
}
