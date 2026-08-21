package co.agrovision.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tabla de capturas.
 *
 * Es el corazón de la fuente única de verdad local: la fila existe antes de que
 * se intente cualquier operación remota, y esa es la razón por la que una
 * captura jamás se pierde (§9.1).
 */
@Entity(
    tableName = "captures",
    indices = [
        // Historial: se ordena por fecha descendente en cada apertura.
        Index(value = ["captured_at"]),
        // Cola de subida: filtra por estado y prioridad en cada sincronización.
        // Sin este índice, `nextBatchToUpload` hace un recorrido completo sobre
        // una tabla que crece sin techo en zona sin señal.
        Index(value = ["sync_state", "next_attempt_at"]),
    ],
)
data class CaptureEntity(
    /** UUIDv7: ordenable por tiempo e idempotente frente al servidor. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Ruta dentro de `filesDir`, nunca MediaStore (§9.3). */
    @ColumnInfo(name = "image_path")
    val imagePath: String,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String,

    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,

    @ColumnInfo(name = "latitude")
    val latitude: Double?,

    @ColumnInfo(name = "longitude")
    val longitude: Double?,

    @ColumnInfo(name = "location_accuracy_m")
    val locationAccuracyM: Float?,

    @ColumnInfo(name = "crop_type")
    val cropType: String?,

    @ColumnInfo(name = "plant_organ")
    val plantOrgan: String?,

    /** Crítico para atribución: sin esto no se puede saber qué versión produjo
     *  una corrección, y sin eso no hay indicador de exactitud de campo. */
    @ColumnInfo(name = "model_version")
    val modelVersion: String,

    @ColumnInfo(name = "predicted_class_id")
    val predictedClassId: String?,

    @ColumnInfo(name = "calibrated_confidence")
    val calibratedConfidence: Float?,

    @ColumnInfo(name = "top3_json")
    val top3Json: String?,

    /** float16 comprimido (§9.2). */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray?,

    @ColumnInfo(name = "ood_score")
    val oodScore: Float,

    /** IDENTIFIED | UNCERTAIN | OOD | QUALITY_REJECTED */
    @ColumnInfo(name = "gate_outcome")
    val gateOutcome: String,

    /** Dato de entrenamiento premium. */
    @ColumnInfo(name = "user_correction_class_id")
    val userCorrectionClassId: String?,

    @ColumnInfo(name = "user_confirmed")
    val userConfirmed: Boolean,

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

    /** Tamaño en disco de imagen + miniatura. Permite mostrar el volumen
     *  pendiente en megabytes antes de gastar datos prepago (§9.4). */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
) {
    // ByteArray compara por identidad: Room genera `equals` de data class y sin
    // esto dos filas idénticas se consideran distintas.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureEntity) return false
        return id == other.id &&
            imagePath == other.imagePath &&
            capturedAt == other.capturedAt &&
            syncState == other.syncState &&
            userConfirmed == other.userConfirmed &&
            userCorrectionClassId == other.userCorrectionClassId &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + capturedAt.hashCode()
        result = 31 * result + syncState.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
