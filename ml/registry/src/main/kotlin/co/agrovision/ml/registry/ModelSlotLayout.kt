package co.agrovision.ml.registry

import android.content.Context
import co.agrovision.core.model.mlmodel.ModelSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disposición en disco de las dos ranuras de modelo.
 *
 * ```
 *   filesDir/models/
 *     ├── slot_a/
 *     │     ├── model.tflite
 *     │     ├── labels.json
 *     │     ├── calibration.json
 *     │     └── manifest.json     ← versión, canal, sha256, fecha de instalación
 *     ├── slot_b/
 *     │     └── …
 *     └── staging/                ← descarga en curso, se limpia al arrancar
 * ```
 *
 * La descarga va siempre a `staging/` y solo se mueve a la ranura inactiva cuando
 * hash y firma han pasado. Así una descarga interrumpida —lo normal sobre 3G en
 * ladera— nunca deja una ranura a medias que parezca instalada.
 *
 * Y la ranura **activa** no se toca jamás durante una instalación. LiteRT mantiene
 * su `model.tflite` mapeado en memoria mientras el intérprete existe; escribir
 * sobre él produce un fallo de segmentación nativo sin traza aprovechable
 * (§23, error nº 11). El esquema de dos ranuras elimina esa clase de error por
 * construcción, no por disciplina.
 */
@Singleton
class ModelSlotLayout @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val root: File get() = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun slotDir(slot: ModelSlot): File = File(root, slot.directoryName).apply { mkdirs() }

    fun stagingDir(): File = File(root, STAGING_DIR).apply { mkdirs() }

    fun modelFile(slot: ModelSlot) = File(slotDir(slot), MODEL_FILE)
    fun labelsFile(slot: ModelSlot) = File(slotDir(slot), LABELS_FILE)
    fun calibrationFile(slot: ModelSlot) = File(slotDir(slot), CALIBRATION_FILE)
    fun manifestFile(slot: ModelSlot) = File(slotDir(slot), MANIFEST_FILE)

    fun isInstalled(slot: ModelSlot): Boolean =
        modelFile(slot).isFile && labelsFile(slot).isFile && manifestFile(slot).isFile

    /**
     * Limpia restos de descargas interrumpidas. Se llama al arrancar.
     *
     * Sin esto, un usuario que pierde la señal a mitad de cada descarga acumula
     * megabytes de basura en un teléfono que probablemente ya está lleno.
     */
    fun clearStaging() {
        stagingDir().listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Vacía una ranura por completo antes de instalar en ella.
     *
     * Solo se invoca sobre la ranura **inactiva**. Quien llame con la activa
     * provoca exactamente el fallo que las ranuras existen para impedir, así que
     * la comprobación vive en [ArtifactInstaller] y no aquí: este objeto no sabe
     * cuál está activa, y darle esa responsabilidad lo convertiría en el sitio
     * donde todo se entera de todo.
     */
    fun clear(slot: ModelSlot) {
        slotDir(slot).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val STAGING_DIR = "staging"
        const val MODEL_FILE = "model.tflite"
        const val LABELS_FILE = "labels.json"
        const val CALIBRATION_FILE = "calibration.json"
        const val MANIFEST_FILE = "manifest.json"
    }
}
