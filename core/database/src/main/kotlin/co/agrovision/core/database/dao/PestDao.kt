package co.agrovision.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import co.agrovision.core.database.entity.PestEntity
import co.agrovision.core.database.entity.SyncCursorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PestDao {

    @Query("SELECT * FROM pest_taxa ORDER BY common_name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PestEntity>>

    @Query("SELECT * FROM pest_taxa WHERE id = :id")
    fun observeById(id: String): Flow<PestEntity?>

    @Query("SELECT * FROM pest_taxa WHERE id = :id")
    suspend fun findById(id: String): PestEntity?

    /**
     * Búsqueda sobre nombre común, científico **y sinónimos regionales**.
     *
     * `local_synonyms_json` se busca como texto crudo a propósito: los sinónimos
     * son pocos por ficha y el catálogo tiene decenas de filas, no millones.
     * Montar FTS aquí sería complejidad sin beneficio medible.
     */
    @Query(
        """
        SELECT * FROM pest_taxa
        WHERE common_name LIKE '%' || :query || '%' COLLATE NOCASE
           OR scientific_name LIKE '%' || :query || '%' COLLATE NOCASE
           OR local_synonyms_json LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY common_name COLLATE NOCASE ASC
        """
    )
    suspend fun search(query: String): List<PestEntity>

    @Upsert
    suspend fun upsertAll(taxa: List<PestEntity>)

    @Query("DELETE FROM pest_taxa WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM pest_taxa")
    suspend fun count(): Int
}

@Dao
interface SyncCursorDao {

    @Query("SELECT * FROM sync_cursors WHERE resource = :resource")
    suspend fun find(resource: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}
