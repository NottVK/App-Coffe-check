package co.agrovision.core.data.legacy

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.database.entity.CaptureEntity
import co.agrovision.core.model.port.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pruebas de la importación desde Capacitor.
 *
 * Esta ruta se ejecuta **una sola vez por instalación** y su fallo es invisible:
 * el usuario abre la app y su galería no está. No hay reporte de error, no hay
 * excepción, no hay forma de recuperarlo. Por eso los casos de aquí no son
 * exhaustividad por gusto: son la única red que tiene.
 *
 * Los escenarios corresponden uno a uno con la lista obligatoria de
 * `docs/adr/0013-migracion-capacitor-a-room.md`.
 */
@RunWith(RobolectricTestRunner::class)
class CapacitorDataImporterTest {

    private lateinit var context: Context
    private lateinit var dao: FakeCaptureDao
    private lateinit var importer: CapacitorDataImporter

    private val fixedClock = Clock { 1_700_000_000_000L }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences(CapacitorDataImporter.CAPACITOR_STORE, Context.MODE_PRIVATE)
            .edit().clear().commit()

        dao = FakeCaptureDao()
        importer = CapacitorDataImporter(context, dao, fixedClock, Dispatchers.Unconfined)
    }

    // ── Caso base: usuario sin nada ──────────────────────────────────────────

    @Test
    fun `un usuario sin fotos previas no produce ninguna fila ni ningun error`() = runTest {
        val report = importer.import()

        assertEquals(0, report.found)
        assertEquals(0, report.imported)
        assertFalse(report.fatalParseError)
        assertTrue(report.shouldMarkComplete)
        assertTrue(dao.rows.isEmpty())
    }

    // ── Camino feliz ─────────────────────────────────────────────────────────

    @Test
    fun `importa una foto y deriva la fecha de captura del nombre del archivo`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        val report = importer.import()

        assertEquals(1, report.imported)
        val row = dao.rows.single()
        // La fecha sale del nombre, no de lastModified: lastModified se pierde
        // al copiar la app entre dispositivos o al restaurar una copia.
        assertEquals(1_722_384_000_000L, row.capturedAt)
        assertEquals("legacy-1722384000000", row.id)
    }

    @Test
    fun `importa cuarenta fotos sin descartar ninguna`() = runTest {
        val names = (0 until 40).map { "17223840000${it.toString().padStart(2, '0')}.jpeg" }
        names.forEach { writePhoto(it) }
        storePreference(photosJson(*names.toTypedArray()))

        val report = importer.import()

        assertEquals(40, report.found)
        assertEquals(40, report.imported)
        assertEquals(0, report.skippedMissingFile)
        assertEquals(0, report.skippedMalformed)
    }

    // ── Lo que de verdad pasa en teléfonos reales ────────────────────────────

    @Test
    fun `las fotos importadas nunca se marcan para subir sin consentimiento`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        importer.import()

        // El usuario nunca consintió que estas imágenes alimentaran el
        // entrenamiento. Asumirlo retroactivamente es lo que §12.2 prohíbe.
        assertEquals("SKIPPED", dao.rows.single().syncState)
    }

    @Test
    fun `las fotos importadas no contaminan las metricas de ninguna version de modelo`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        importer.import()

        val row = dao.rows.single()
        assertEquals(CapacitorDataImporter.LEGACY_MODEL_VERSION, row.modelVersion)
        // No hubo compuertas cuando se tomó esta foto: inventar un IDENTIFIED
        // sería fabricar un diagnóstico que nadie hizo.
        assertEquals("UNCERTAIN", row.gateOutcome)
        assertEquals(null, row.predictedClassId)
    }

    @Test
    fun `genera miniatura porque la app anterior no las tenia`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        importer.import()

        val thumbnail = File(dao.rows.single().thumbnailPath)
        assertTrue("la miniatura debe existir en disco", thumbnail.isFile)
        assertTrue("la miniatura no puede estar vacía", thumbnail.length() > 0)
    }

    // ── Tolerancia: una entrada mala no puede costar las demás ───────────────

    @Test
    fun `una entrada cuyo archivo ya no existe se descarta sin perder las demas`() = runTest {
        writePhoto("1722384000001.jpeg")
        // La primera entrada apunta a un archivo que el usuario borró.
        storePreference(photosJson("1722384000000.jpeg", "1722384000001.jpeg"))

        val report = importer.import()

        assertEquals(2, report.found)
        assertEquals(1, report.imported)
        assertEquals(1, report.skippedMissingFile)
        assertEquals("legacy-1722384000001", dao.rows.single().id)
    }

    @Test
    fun `una entrada sin webPath ni filePath se descarta sin abortar`() = runTest {
        writePhoto("1722384000001.jpeg")
        storePreference(
            """[{"webViewPath":"blob:algo"},{"webPath":"1722384000001.jpeg","filePath":"file:///x"}]"""
        )

        val report = importer.import()

        assertEquals(1, report.imported)
        assertEquals(1, report.skippedMalformed)
    }

    @Test
    fun `un json truncado a media cadena no se marca como completado`() = runTest {
        storePreference("""[{"webPath":"1722384000000.jpe""")

        val report = importer.import()

        assertTrue(report.fatalParseError)
        // Clave: NO se marca completada. Los datos originales siguen intactos y
        // una versión futura con mejor parseo puede reintentarlo.
        assertFalse(report.shouldMarkComplete)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `un valor que no es un array se trata como fallo total y no como vacio`() = runTest {
        storePreference("""{"photos":[]}""")

        val report = importer.import()

        assertTrue(report.fatalParseError)
        assertFalse(report.shouldMarkComplete)
    }

    // ── Idempotencia ─────────────────────────────────────────────────────────

    @Test
    fun `reejecutar tras una importacion completa no duplica nada`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        importer.import()
        val second = importer.import()

        assertEquals(1, dao.rows.size)
        assertEquals(0, second.imported)
        assertEquals(1, second.skippedDuplicate)
    }

    @Test
    fun `una importacion interrumpida a la mitad se completa al reejecutarse`() = runTest {
        writePhoto("1722384000000.jpeg")
        writePhoto("1722384000001.jpeg")
        storePreference(photosJson("1722384000000.jpeg", "1722384000001.jpeg"))

        // Simula que el proceso murió tras escribir la primera fila: la bandera
        // global no está puesta, pero hay datos a medias en Room.
        dao.rows += dao.buildStub("legacy-1722384000000")

        val report = importer.import()

        assertEquals(1, report.imported)
        assertEquals(1, report.skippedDuplicate)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `la importacion no borra los datos originales de capacitor`() = runTest {
        writePhoto("1722384000000.jpeg")
        storePreference(photosJson("1722384000000.jpeg"))

        importer.import()

        // No destructiva (ADR-013): si esto salió mal, la próxima versión
        // todavía puede releerlo.
        val preferences = context.getSharedPreferences(
            CapacitorDataImporter.CAPACITOR_STORE,
            Context.MODE_PRIVATE,
        )
        assertTrue(preferences.contains(CapacitorDataImporter.PHOTOS_KEY))
        assertTrue(File(context.filesDir, "1722384000000.jpeg").isFile)
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private fun writePhoto(name: String) {
        val file = File(context.filesDir, name)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    private fun photosJson(vararg names: String): String =
        names.joinToString(prefix = "[", postfix = "]") { name ->
            """{"webPath":"$name","filePath":"file://${context.filesDir}/$name","webViewPath":"blob:x"}"""
        }

    private fun storePreference(value: String) {
        context.getSharedPreferences(CapacitorDataImporter.CAPACITOR_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(CapacitorDataImporter.PHOTOS_KEY, value)
            .commit()
    }
}

/**
 * DAO falso en memoria. Se prefiere a un mock porque la prueba necesita
 * comportamiento real de «insertar y luego encontrar» para verificar la
 * idempotencia, y un mock de eso sería reimplementar el DAO con menos garantías.
 */
private class FakeCaptureDao : CaptureDao {

    val rows = mutableListOf<CaptureEntity>()

    override suspend fun insert(capture: CaptureEntity) {
        require(rows.none { it.id == capture.id }) { "clave primaria duplicada: ${capture.id}" }
        rows += capture
    }

    override suspend fun findById(id: String): CaptureEntity? = rows.firstOrNull { it.id == id }

    fun buildStub(id: String) = CaptureEntity(
        id = id,
        imagePath = "/stub/$id.jpg",
        thumbnailPath = "/stub/$id-thumb.jpg",
        capturedAt = 0L,
        latitude = null,
        longitude = null,
        locationAccuracyM = null,
        cropType = null,
        plantOrgan = null,
        modelVersion = CapacitorDataImporter.LEGACY_MODEL_VERSION,
        predictedClassId = null,
        calibratedConfidence = null,
        top3Json = null,
        embedding = null,
        oodScore = 0f,
        gateOutcome = "UNCERTAIN",
        userCorrectionClassId = null,
        userConfirmed = false,
        syncState = "SKIPPED",
        syncAttempts = 0,
        lastSyncError = null,
        nextAttemptAt = null,
        serverId = null,
        sizeBytes = 0L,
    )

    // ── Sin uso en estas pruebas ──
    override fun observeAll() = throw NotImplementedError()
    override fun observeById(id: String) = throw NotImplementedError()
    override fun observePendingCount() = throw NotImplementedError()
    override fun observePendingBytes() = throw NotImplementedError()
    override suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long) = emptyList<CaptureEntity>()
    override suspend fun updateSyncState(
        id: String,
        state: String,
        serverId: String?,
        error: String?,
        nextAttemptAt: Long?,
    ) = Unit
    override suspend fun confirm(id: String) = 0
    override suspend fun correct(id: String, classId: String) = 0
    override suspend fun findPurgeable(cutoffEpochMillis: Long) = emptyList<CaptureEntity>()
    override suspend fun deleteByIds(ids: List<String>) = 0
}
