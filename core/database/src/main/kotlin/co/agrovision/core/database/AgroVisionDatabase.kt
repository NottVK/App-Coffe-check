package co.agrovision.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.database.dao.PestDao
import co.agrovision.core.database.dao.ReviewDao
import co.agrovision.core.database.dao.SyncCursorDao
import co.agrovision.core.database.entity.CaptureEntity
import co.agrovision.core.database.entity.PestEntity
import co.agrovision.core.database.entity.ReviewImageEntity
import co.agrovision.core.database.entity.ReviewRequestEntity
import co.agrovision.core.database.entity.SyncCursorEntity

/**
 * Base de datos local. **Fuente única de verdad de la aplicación** (§9.1).
 *
 * `exportSchema = true` no es negociable: sin los JSON de esquema en
 * `core/database/schemas/` no existe `MigrationTestHelper`, sin
 * `MigrationTestHelper` no hay forma de probar una migración, y una migración
 * sin probar es el camino directo a que alguien la «arregle» con una migración
 * destructiva — el error prohibido nº 2 (§23), que borra las capturas que el
 * productor tomó sin señal y todavía no ha subido.
 *
 * Cómo añadir una migración:
 *  1. Sube `version` en esta anotación.
 *  2. Escribe la `Migration` en [AgroVisionMigrations] y añádela a `ALL`.
 *  3. Compila una vez para que KSP genere el JSON de la versión nueva.
 *  4. Añade la prueba en `MigrationTest`. **En ambos sentidos.**
 *  5. Confirma en Git el JSON nuevo junto al código.
 *
 * Saltarse el paso 5 rompe la compilación de todos los demás.
 */
@Database(
    entities = [
        CaptureEntity::class,
        PestEntity::class,
        ReviewRequestEntity::class,
        ReviewImageEntity::class,
        SyncCursorEntity::class,
    ],
    version = AgroVisionDatabase.VERSION,
    exportSchema = true,
)
abstract class AgroVisionDatabase : RoomDatabase() {

    abstract fun captureDao(): CaptureDao
    abstract fun pestDao(): PestDao
    abstract fun reviewDao(): ReviewDao
    abstract fun syncCursorDao(): SyncCursorDao

    companion object {
        const val VERSION = 2
        const val NAME = "agrovision.db"
    }
}
