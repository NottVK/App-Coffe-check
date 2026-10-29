package co.agrovision.core.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Migraciones de Room contra los JSON de `schemas/`. Nunca se usa migración
 * destructiva: lo que el productor capturó sin señal tiene que sobrevivir.
 *
 * Instrumentada: necesita un dispositivo o emulador.
 */
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgroVisionDatabase::class.java,
    )

    @Test
    fun migracion_2_a_3_conserva_capturas_y_solicitudes() {
        helper.createDatabase(DB, 2).use { db ->
            db.insertCapture(id = "cap-1", latitude = 2.93, longitude = -75.28)
            db.insertCapture(id = "cap-2", latitude = null, longitude = null)
            db.insertReview(id = "req-1", latitude = 2.93, longitude = -75.28)
        }

        val migrated = helper.runMigrationsAndValidate(DB, 3, true, AgroVisionMigrations.MIGRATION_2_3)

        migrated.query("SELECT id, latitude, longitude, location_accuracy_m, model_version, size_bytes, " +
            "altitude_m, location_is_mock, location_is_exact FROM captures ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("cap-1", c.getString(0))
            // Latitud y longitud intactas: no se pierde ningún dato del productor.
            assertEquals(2.93, c.getDouble(1), 0.0)
            assertEquals(-75.28, c.getDouble(2), 0.0)
            assertEquals("2.0.0", c.getString(4))
            assertEquals(2048L, c.getLong(5))
            assertTrue("sin altitud en filas viejas", c.isNull(6))
            assertEquals("no es un fix simulado", 0, c.getInt(7))
            // Origen incierto (pudo guardarse redondeada): nunca se asume exacta.
            assertEquals(0, c.getInt(8))
            c.moveToNext()
            assertTrue(c.isNull(1))
            assertEquals(0, c.getInt(8))
        }

        migrated.query("SELECT latitude, longitude, altitude_m, location_is_mock, location_is_exact " +
            "FROM review_requests WHERE id = 'req-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2.93, c.getDouble(0), 0.0)
            assertEquals(-75.28, c.getDouble(1), 0.0)
            assertTrue(c.isNull(2))
            assertEquals(0, c.getInt(3))
            assertEquals(0, c.getInt(4))
        }
    }

    @Test
    fun las_filas_nuevas_pueden_guardar_exacta_altitud_y_mock() {
        helper.createDatabase(DB, 2).close()
        val migrated = helper.runMigrationsAndValidate(DB, 3, true, AgroVisionMigrations.MIGRATION_2_3)

        migrated.execSQL(
            "INSERT INTO review_requests (id, crop_type, plant_organ, created_at, status, resolution_read, " +
                "sync_state, sync_attempts, latitude, longitude, altitude_m, location_is_mock, location_is_exact) " +
                "VALUES ('r', 'CAFE', 'HOJA', 1, 'PENDING_UPLOAD', 0, 'PENDING', 0, 2.9, -75.2, 1432.5, 1, 1)"
        )
        migrated.query("SELECT altitude_m, location_is_mock, location_is_exact FROM review_requests").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1432.5, c.getDouble(0), 0.0)
            assertEquals(1, c.getInt(1))
            assertEquals(1, c.getInt(2))
        }
    }

    @Test
    fun la_cadena_completa_1_a_3_llega_al_esquema_actual() {
        helper.createDatabase(DB, 1).close()

        helper.runMigrationsAndValidate(DB, 3, true, *AgroVisionMigrations.ALL).close()
    }

    @Test
    fun room_abre_la_base_migrada_y_lee_las_filas_con_los_DAO_reales() = runBlocking {
        helper.createDatabase(DB, 2).use { db ->
            db.insertCapture(id = "cap-1", latitude = 2.93, longitude = -75.28)
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AgroVisionDatabase::class.java, DB)
            .addMigrations(*AgroVisionMigrations.ALL)
            .build()
        try {
            val capture = room.captureDao().findById("cap-1")
            assertEquals(2.93, capture?.latitude)
            assertFalse(capture?.locationIsExact ?: true)
            assertFalse(capture?.locationIsMock ?: true)
        } finally {
            room.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertCapture(
        id: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("image_path", "/fotos/$id.jpg")
            put("thumbnail_path", "/fotos/$id.thumb.jpg")
            put("captured_at", 1_700_000_000_000L)
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            put("model_version", "2.0.0")
            put("ood_score", 0.5)
            put("gate_outcome", "UNCERTAIN")
            put("user_confirmed", 0)
            put("sync_state", "PENDING")
            put("sync_attempts", 0)
            put("size_bytes", 2048L)
        }
        insert("captures", SQLiteDatabase.CONFLICT_FAIL, values)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertReview(
        id: String,
        latitude: Double?,
        longitude: Double?,
    ) {
        val values = ContentValues().apply {
            put("id", id)
            put("crop_type", "CAFE")
            put("plant_organ", "HOJA")
            latitude?.let { put("latitude", it) }
            longitude?.let { put("longitude", it) }
            put("created_at", 1_700_000_000_000L)
            put("status", "PENDING_UPLOAD")
            put("resolution_read", 0)
            put("sync_state", "PENDING")
            put("sync_attempts", 0)
        }
        insert("review_requests", SQLiteDatabase.CONFLICT_FAIL, values)
    }

    private companion object {
        const val DB = "migration-test"
    }
}
