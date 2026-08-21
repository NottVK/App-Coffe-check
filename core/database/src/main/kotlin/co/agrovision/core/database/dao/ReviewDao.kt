package co.agrovision.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import co.agrovision.core.database.entity.ReviewImageEntity
import co.agrovision.core.database.entity.ReviewRequestEntity
import co.agrovision.core.database.entity.ReviewRequestWithImages
import kotlinx.coroutines.flow.Flow

@Dao
interface ReviewDao {

    @Transaction
    @Query("SELECT * FROM review_requests ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ReviewRequestWithImages>>

    @Transaction
    @Query("SELECT * FROM review_requests WHERE id = :id")
    fun observeById(id: String): Flow<ReviewRequestWithImages?>

    /**
     * Resoluciones que el productor todavía no ha visto.
     *
     * Alimenta el distintivo de la pestaña. Que una respuesta de agrónomo llegue
     * y pase desapercibida es lo mismo que no haber respondido: el productor
     * concluye que nadie le contestó y deja de enviar solicitudes (§1.2).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM review_requests
        WHERE status = 'RESOLVED' AND resolution_read = 0
        ORDER BY resolved_at DESC
        """
    )
    fun observeUnreadResolutions(): Flow<List<ReviewRequestWithImages>>

    @Transaction
    @Query(
        """
        SELECT * FROM review_requests
        WHERE sync_state = 'PENDING'
          AND (next_attempt_at IS NULL OR next_attempt_at <= :nowEpochMillis)
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextBatchToUpload(limit: Int, nowEpochMillis: Long): List<ReviewRequestWithImages>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequest(request: ReviewRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImages(images: List<ReviewImageEntity>)

    @Upsert
    suspend fun upsertRequest(request: ReviewRequestEntity)

    /** Solicitud e imágenes se escriben o no se escriben. Una solicitud sin sus
     *  imágenes es una solicitud que el agrónomo no puede resolver. */
    @Transaction
    suspend fun insertWithImages(request: ReviewRequestEntity, images: List<ReviewImageEntity>) {
        insertRequest(request)
        insertImages(images)
    }

    @Query("UPDATE review_requests SET resolution_read = 1 WHERE id = :id")
    suspend fun markResolutionRead(id: String)

    @Query("SELECT COUNT(*) FROM review_requests WHERE sync_state = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}
