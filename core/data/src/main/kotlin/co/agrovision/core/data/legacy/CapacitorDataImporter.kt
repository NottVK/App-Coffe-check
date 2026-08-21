package co.agrovision.core.data.legacy

import android.content.Context
import android.graphics.BitmapFactory
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.database.entity.CaptureEntity
import co.agrovision.core.model.port.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Importa la galería de la app publicada (Ionic + Capacitor) a Room.
 *
 * **Esta es la pieza de mayor riesgo del refactor** y la única cuyo fallo no
 * produce ningún error visible: simplemente, el usuario actualiza, abre la app y
 * su galería está vacía. No hay forma de recuperarlo después.
 *
 * Ver `docs/adr/0013-migracion-capacitor-a-room.md` y `PRESERVE.md` §5.
 *
 * ## Qué guarda la app publicada
 *
 * `src/app/services/photo.service.ts` usa `@capacitor/preferences`, que en
 * Android es un `SharedPreferences` llamado `CapacitorStorage`:
 *
 * ```
 *   CapacitorStorage / "photos" → [{"webPath","filePath","webViewPath"}, ...]
 * ```
 *
 * Y `@capacitor/filesystem` con `Directory.Data`, que es `filesDir`. Los archivos
 * se llaman `<epochMillis>.jpeg`, lo que resulta útil: de ahí sale la fecha de
 * captura sin depender de la marca temporal del sistema de archivos, que se
 * pierde al copiar.
 *
 * ## Principios de la implementación
 *
 * **Tolerante.** Una entrada mala no puede costar las otras treinta y nueve. Todo
 * lo que falla se cuenta y se salta.
 *
 * **Idempotente.** Interrumpirla y reejecutarla llega al mismo estado. Se
 * comprueba fila por fila, no solo con la bandera global: si el proceso murió a
 * mitad, la bandera no está puesta pero hay filas escritas.
 *
 * **No destructiva.** No se borra nada de `CapacitorStorage` ni de `filesDir`. Si
 * esto salió mal, la próxima versión todavía puede releerlo.
 */
@Singleton
class CapacitorDataImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val captures: CaptureDao,
    private val clock: Clock,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun import(): ImportReport = withContext(io) {
        val preferences = context.getSharedPreferences(CAPACITOR_STORE, Context.MODE_PRIVATE)
        val raw = preferences.getString(PHOTOS_KEY, null)

        if (raw.isNullOrBlank()) {
            return@withContext ImportReport.nothingToDo()
        }

        val entries = parseEntries(raw)
        if (entries == null) {
            // JSON ilegible por completo. Se registra y NO se marca como
            // completada: si una versión futura arregla el parseo, podrá
            // reintentarlo sobre los datos, que siguen intactos.
            return@withContext ImportReport(
                found = 0,
                imported = 0,
                skippedMissingFile = 0,
                skippedMalformed = 1,
                skippedDuplicate = 0,
                fatalParseError = true,
            )
        }

        var imported = 0
        var missingFile = 0
        var malformed = 0
        var duplicate = 0

        entries.forEach { entry ->
            val fileName = entry.fileName
            if (fileName == null) {
                malformed++
                return@forEach
            }

            val source = resolveFile(fileName, entry.filePath)
            if (source == null || !source.isFile) {
                missingFile++
                return@forEach
            }

            val id = legacyIdFor(fileName)
            if (captures.findById(id) != null) {
                duplicate++
                return@forEach
            }

            val row = buildRow(id, source, fileName)
            if (row == null) {
                malformed++
                return@forEach
            }

            runCatching { captures.insert(row) }
                .onSuccess { imported++ }
                .onFailure { malformed++ }
        }

        ImportReport(
            found = entries.size,
            imported = imported,
            skippedMissingFile = missingFile,
            skippedMalformed = malformed,
            skippedDuplicate = duplicate,
            fatalParseError = false,
        )
    }

    // ── Lectura del formato heredado ─────────────────────────────────────────

    /**
     * Parsea con las manos abiertas. El JSON lo escribió `JSON.stringify` de una
     * WebView a lo largo de meses y versiones distintas de la app: puede haber
     * campos ausentes, nulos y objetos que no son objetos.
     *
     * Devuelve `null` solo si ni siquiera es un array — el único caso en el que no
     * hay nada que rescatar.
     */
    private fun parseEntries(raw: String): List<LegacyPhoto>? = try {
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return null
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull LegacyPhoto(null, null)
            LegacyPhoto(
                webPath = obj["webPath"]?.jsonPrimitive?.contentOrNullSafe(),
                filePath = obj["filePath"]?.jsonPrimitive?.contentOrNullSafe(),
            )
        }
    } catch (error: Exception) {
        // Truncado a media cadena, comillas sin cerrar, bytes basura.
        null
    }

    /**
     * `webPath` es el nombre de archivo (`1722384000000.jpeg`); `filePath` es la
     * URI absoluta que devolvió `Filesystem.writeFile`. Se prefiere el nombre y
     * se resuelve contra `filesDir` porque la URI absoluta contiene el
     * `applicationId` y la ruta del sandbox, que cambian entre reinstalaciones y
     * entre perfiles de usuario de Android.
     */
    private fun resolveFile(fileName: String, filePath: String?): File? {
        val direct = File(context.filesDir, fileName)
        if (direct.isFile) return direct

        // Respaldo: la URI absoluta, si sigue siendo válida.
        val fromUri = filePath
            ?.removePrefix("file://")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        return fromUri?.takeIf { it.isFile }
    }

    private fun buildRow(id: String, source: File, fileName: String): CaptureEntity? {
        val thumbnailPath = generateThumbnail(id, source) ?: return null

        return CaptureEntity(
            id = id,
            imagePath = source.absolutePath,
            thumbnailPath = thumbnailPath,
            // El nombre del archivo ES la marca temporal de captura. Es más
            // fiable que `lastModified`, que se pierde al copiar o restaurar.
            capturedAt = fileName.substringBefore('.').toLongOrNull()
                ?: source.lastModified().takeIf { it > 0 }
                ?: clock.nowEpochMillis(),
            latitude = null,
            longitude = null,
            locationAccuracyM = null,
            cropType = null,
            plantOrgan = null,
            // Etiqueta propia: estas filas NO deben contaminar las métricas de
            // exactitud de campo de ninguna versión de modelo real.
            modelVersion = LEGACY_MODEL_VERSION,
            predictedClassId = null,
            calibratedConfidence = null,
            top3Json = null,
            embedding = null,
            oodScore = 0f,
            // No hubo compuertas cuando se tomó esta foto. UNCERTAIN es honesto;
            // IDENTIFIED sería inventarse un diagnóstico que nadie hizo.
            gateOutcome = "UNCERTAIN",
            userCorrectionClassId = null,
            userConfirmed = false,
            // SKIPPED, no PENDING. El usuario nunca dio consentimiento para que
            // estas imágenes alimentaran el entrenamiento, y asumirlo
            // retroactivamente es exactamente lo que §12.2 prohíbe. Se le pedirá
            // después, en contexto, y entonces pasarán a PENDING.
            syncState = "SKIPPED",
            syncAttempts = 0,
            lastSyncError = null,
            nextAttemptAt = null,
            serverId = null,
            sizeBytes = source.length() + File(thumbnailPath).length(),
        )
    }

    /**
     * La app anterior no generaba miniaturas. Sin ellas el historial tendría que
     * decodificar imágenes completas al desplazarse, que en un teléfono de 2 GB
     * es un cierre por memoria esperando a ocurrir.
     *
     * `inSampleSize` decodifica ya reducido: nunca se carga la imagen completa en
     * el montículo.
     */
    private fun generateThumbnail(id: String, source: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(
                maxOf(bounds.outWidth, bounds.outHeight),
                AgroConstants.Image.THUMBNAIL_PX,
            )
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null

        val target = File(File(context.filesDir, THUMBNAILS_DIR).apply { mkdirs() }, "$id.jpg")
        target.outputStream().use { output ->
            bitmap.compress(
                android.graphics.Bitmap.CompressFormat.JPEG,
                AgroConstants.Image.JPEG_QUALITY,
                output,
            )
        }
        bitmap.recycle()
        target.absolutePath
    }.getOrNull()

    private fun sampleSizeFor(sourceLongSide: Int, targetLongSide: Int): Int {
        var sample = 1
        while (sourceLongSide / (sample * 2) >= targetLongSide) sample *= 2
        return sample
    }

    /** Determinista sobre el nombre del archivo: reejecutar la importación
     *  produce el mismo identificador y por tanto no duplica. */
    private fun legacyIdFor(fileName: String): String =
        "$LEGACY_ID_PREFIX${fileName.substringBefore('.')}"

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        content.takeIf { it.isNotBlank() && it != "null" }

    private data class LegacyPhoto(val webPath: String?, val filePath: String?) {
        /** El nombre sale de `webPath`, o del último segmento de `filePath`. */
        val fileName: String? =
            webPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: filePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** Nombre del SharedPreferences que crea `@capacitor/preferences`. */
        const val CAPACITOR_STORE = "CapacitorStorage"
        const val PHOTOS_KEY = "photos"
        const val LEGACY_MODEL_VERSION = "legacy-tm-1.0"
        const val LEGACY_ID_PREFIX = "legacy-"
        private const val THUMBNAILS_DIR = "thumbnails"
    }
}

/**
 * Resultado de la importación. Se registra en telemetría: es la única forma de
 * saber si esto funcionó en los teléfonos reales, porque nadie va a reportar
 * «no me apareció una galería que no sabía que iba a aparecer».
 */
data class ImportReport(
    val found: Int,
    val imported: Int,
    val skippedMissingFile: Int,
    val skippedMalformed: Int,
    val skippedDuplicate: Int,
    val fatalParseError: Boolean,
) {
    /**
     * Solo se marca como completada si no hubo un fallo de parseo total. Con
     * entradas sueltas descartadas sí se cierra: reintentar no las arreglaría, y
     * los datos originales siguen ahí por si acaso.
     */
    val shouldMarkComplete: Boolean get() = !fatalParseError

    val hadWork: Boolean get() = found > 0

    companion object {
        fun nothingToDo() = ImportReport(0, 0, 0, 0, 0, fatalParseError = false)
    }
}
