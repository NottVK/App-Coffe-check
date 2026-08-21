package co.agrovision.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Catálogo de plagas. Vive completo en el dispositivo: no es una caché de la
 * API, es la fuente que consulta el productor en el lote (§3.1 RF-08).
 */
@Entity(
    tableName = "pest_taxa",
    indices = [Index(value = ["crop"]), Index(value = ["common_name"])],
)
data class PestEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "scientific_name")
    val scientificName: String,

    @ColumnInfo(name = "common_name")
    val commonName: String,

    /** JSON. Los sinónimos regionales son cómo el productor busca de verdad. */
    @ColumnInfo(name = "local_synonyms_json")
    val localSynonymsJson: String,

    @ColumnInfo(name = "crop")
    val crop: String,

    @ColumnInfo(name = "affected_organs_json")
    val affectedOrgansJson: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "recommendations_json")
    val recommendationsJson: String,

    @ColumnInfo(name = "image_refs_json")
    val imageRefsJson: String,

    @ColumnInfo(name = "version")
    val version: Int,

    /** ENFERMEDAD | PLAGA | DEFICIENCIA | SANO | OTRO */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "reference")
    val reference: String?,

    // ── Trazabilidad profesional (§13.6) ──
    // Sin estos tres campos poblados, la ficha se muestra SIN la sección de
    // manejo. Distribuir dosis e ingredientes activos sin aval de agrónomo con
    // tarjeta vigente es exposición legal ante el ICA.
    @ColumnInfo(name = "approved_by_name")
    val approvedByName: String?,

    @ColumnInfo(name = "approved_license")
    val approvedLicense: String?,

    @ColumnInfo(name = "approved_at")
    val approvedAt: Long?,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * Cursor de sincronización incremental. Una sola fila.
 *
 * Guardar el ETag junto a la marca temporal permite que el servidor responda
 * `304 Not Modified` y que la sincronización del catálogo cueste una cabecera
 * en vez de una descarga completa — que sobre datos prepago es la diferencia
 * entre sincronizar y no hacerlo.
 */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "resource")
    val resource: String,

    @ColumnInfo(name = "since_epoch_millis")
    val sinceEpochMillis: Long?,

    @ColumnInfo(name = "etag")
    val etag: String?,
) {
    companion object {
        const val RESOURCE_CATALOG = "catalog"
        const val RESOURCE_RESOLUTIONS = "resolutions"
    }
}
