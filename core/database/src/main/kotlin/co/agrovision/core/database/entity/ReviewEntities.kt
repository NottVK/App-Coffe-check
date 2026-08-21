package co.agrovision.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "review_requests",
    indices = [
        Index(value = ["sync_state", "next_attempt_at"]),
        Index(value = ["created_at"]),
        Index(value = ["status"]),
    ],
)
data class ReviewRequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "capture_id")
    val captureId: String?,

    @ColumnInfo(name = "crop_type")
    val cropType: String,

    @ColumnInfo(name = "plant_organ")
    val plantOrgan: String,

    @ColumnInfo(name = "note_text")
    val noteText: String?,

    @ColumnInfo(name = "note_audio_path")
    val noteAudioPath: String?,

    @ColumnInfo(name = "latitude")
    val latitude: Double?,

    @ColumnInfo(name = "longitude")
    val longitude: Double?,

    @ColumnInfo(name = "location_accuracy_m")
    val locationAccuracyM: Float?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "status")
    val status: String,

    // ── Resolución del agrónomo. Se guarda para que quede consultable sin
    //    conexión: el productor lee la respuesta en el lote, no en el pueblo.
    @ColumnInfo(name = "resolution_type")
    val resolutionType: String?,

    @ColumnInfo(name = "resolved_pest_id")
    val resolvedPestId: String?,

    @ColumnInfo(name = "agronomist_response")
    val agronomistResponse: String?,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long?,

    @ColumnInfo(name = "resolution_read")
    val resolutionRead: Boolean,

    @ColumnInfo(name = "sync_state")
    val syncState: String,

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int,

    @ColumnInfo(name = "last_sync_error")
    val lastSyncError: String?,

    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long?,

    @ColumnInfo(name = "server_id")
    val serverId: String?,
)

/**
 * Una solicitud bien formada incluye varias tomas desde ángulos distintos, de ahí
 * la tabla aparte con relación uno a muchos (§9.2).
 *
 * `CASCADE` al borrar: una imagen huérfana sin su solicitud no tiene ningún uso
 * y sí ocupa espacio en un teléfono que suele estar lleno.
 */
@Entity(
    tableName = "review_request_images",
    foreignKeys = [
        ForeignKey(
            entity = ReviewRequestEntity::class,
            parentColumns = ["id"],
            childColumns = ["review_request_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["review_request_id"])],
)
data class ReviewImageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "review_request_id")
    val reviewRequestId: String,

    @ColumnInfo(name = "image_path")
    val imagePath: String,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String,

    @ColumnInfo(name = "angle")
    val angle: String,

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReviewImageEntity) return false
        return id == other.id &&
            reviewRequestId == other.reviewRequestId &&
            imagePath == other.imagePath &&
            angle == other.angle &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + reviewRequestId.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}

/** Proyección de lectura: solicitud con sus imágenes en una sola consulta. */
data class ReviewRequestWithImages(
    @Embedded val request: ReviewRequestEntity,
    @Relation(parentColumn = "id", entityColumn = "review_request_id")
    val images: List<ReviewImageEntity>,
)
