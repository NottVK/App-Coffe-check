package co.agrovision.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones explícitas. Nunca destructivas.
 *
 * ⚠️ **Antes de tocar este archivo, lee `PRESERVE.md` §2.4.** La aplicación ya
 * está publicada: hay dispositivos en campo con un esquema anterior y con
 * capturas sin subir. La versión de esquema publicada se declara en
 * `roomSchemaVersion` dentro del contrato, y los JSON de la versión publicada
 * deben estar copiados en `core/database/schemas/` **antes** de escribir la
 * primera migración. Sin ellos no se puede probar nada.
 *
 * Estrategia obligatoria, la misma que en el backend: **expand-contract**.
 *
 * ```
 *  v(n)   añadir la columna nueva, nullable, sin tocar la vieja
 *  v(n+1) escribir en ambas; la app lee la nueva con respaldo en la vieja
 *  v(n+2) eliminar la vieja
 * ```
 *
 * Tres migraciones en vez de una, y reversible en cualquier punto. Una
 * migración destructiva en el mismo despliegue que el código que la necesita
 * imposibilita la reversión (§23, error nº 13) — y en móvil «revertir» significa
 * que el usuario instale un APK anterior, algo que no se puede pedir.
 *
 * Plantilla:
 *
 * ```kotlin
 * val MIGRATION_1_2 = object : Migration(1, 2) {
 *     override fun migrate(db: SupportSQLiteDatabase) {
 *         db.execSQL("ALTER TABLE captures ADD COLUMN plant_stage TEXT")
 *     }
 * }
 * ```
 */
object AgroVisionMigrations {

    /**
     * 1 → 2 · El catálogo gana tipo de problema y referencia bibliográfica.
     *
     * `kind` distingue enfermedad de deficiencia nutricional, que exigen acciones
     * opuestas: fungicida contra fertilización. `reference` es la fuente que
     * permite a un técnico verificar de dónde sale la ficha.
     *
     * **Aditiva y no destructiva**, como manda expand-contract: solo añade
     * columnas. Las fichas existentes quedan como `OTRO` hasta que la siguiente
     * sincronización de catálogo las clasifique, y ninguna captura del productor
     * se toca.
     *
     * `NOT NULL DEFAULT 'OTRO'` no es un capricho: `PestEntity.kind` es un
     * `String` no anulable, y Room compara el esquema resultante contra el que
     * genera de la entidad. Sin el `DEFAULT`, la migración corre y la
     * verificación de integridad falla igual.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pest_taxa ADD COLUMN kind TEXT NOT NULL DEFAULT 'OTRO'")
            db.execSQL("ALTER TABLE pest_taxa ADD COLUMN reference TEXT")
        }
    }

    /**
     * Room falla al abrir la base si encuentra una versión instalada para la que
     * no hay ruta de migración, y eso es exactamente lo que debe ocurrir: un
     * fallo ruidoso en desarrollo en lugar de una pérdida silenciosa de datos en
     * campo.
     */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)

    /** Utilidad para migraciones que solo añaden columnas nullable, que es el
     *  99 % del caso real. */
    internal fun addColumn(
        from: Int,
        to: Int,
        table: String,
        column: String,
        type: String,
    ): Migration = object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
        }
    }
}
