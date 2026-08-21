package co.agrovision.core.data.repository

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.database.dao.PestDao
import co.agrovision.core.database.dao.SyncCursorDao
import co.agrovision.core.database.entity.PestEntity
import co.agrovision.core.database.entity.SyncCursorEntity
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.ManagementKind
import co.agrovision.core.model.pest.ManagementRecommendation
import co.agrovision.core.model.pest.PestKind
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.pest.ProfessionalApproval
import co.agrovision.core.model.port.CatalogCursor
import co.agrovision.core.model.port.PestCatalogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PestCatalogRepositoryImpl @Inject constructor(
    private val dao: PestDao,
    private val cursors: SyncCursorDao,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : PestCatalogRepository {

    override fun observeAll(): Flow<List<PestTaxon>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeById(id: String): Flow<PestTaxon?> =
        dao.observeById(id).map { it?.toDomain() }

    override suspend fun findById(id: String): PestTaxon? = withContext(io) {
        dao.findById(id)?.toDomain()
    }

    override suspend fun search(query: String): List<PestTaxon> = withContext(io) {
        dao.search(query).map { it.toDomain() }
    }

    override suspend fun upsertAll(taxa: List<PestTaxon>) = withContext(io) {
        dao.upsertAll(taxa.map { it.toEntity() })
    }

    override suspend fun deleteAll(ids: List<String>) = withContext(io) {
        dao.deleteByIds(ids)
    }

    override suspend fun syncCursor(): CatalogCursor = withContext(io) {
        cursors.find(SyncCursorEntity.RESOURCE_CATALOG)
            ?.let { CatalogCursor(it.sinceEpochMillis, it.etag) }
            ?: CatalogCursor(null, null)
    }

    override suspend fun updateSyncCursor(cursor: CatalogCursor) = withContext(io) {
        cursors.upsert(
            SyncCursorEntity(
                resource = SyncCursorEntity.RESOURCE_CATALOG,
                sinceEpochMillis = cursor.sinceEpochMillis,
                etag = cursor.etag,
            )
        )
    }
}

// ── Mapeo ────────────────────────────────────────────────────────────────────

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class RecommendationJson(
    val kind: String,
    val summary: String,
    val detail: String = "",
    val activeIngredient: String? = null,
    val dosage: String? = null,
    val preHarvestIntervalDays: Int? = null,
)

private val stringListSerializer = ListSerializer(String.serializer())
private val recommendationListSerializer = ListSerializer(RecommendationJson.serializer())

internal fun PestEntity.toDomain(): PestTaxon = PestTaxon(
    id = id,
    scientificName = scientificName,
    commonName = commonName,
    localSynonyms = localSynonymsJson.decodeStrings(),
    crop = CropType.entries.firstOrNull { it.name == crop } ?: CropType.OTRO,
    affectedOrgans = affectedOrgansJson.decodeStrings()
        .mapNotNull { name -> PlantOrgan.entries.firstOrNull { it.name == name } },
    description = description,
    recommendations = recommendationsJson.decodeRecommendations(),
    imageRefs = imageRefsJson.decodeStrings(),
    version = version,
    kind = PestKind.parse(kind),
    reference = reference,
    // El aval solo existe si están los TRES campos. Con uno suelto no hay
    // trazabilidad profesional, y sin trazabilidad la ficha se muestra SIN la
    // sección de manejo (§13.6). Es preferible una ficha incompleta a distribuir
    // dosis de agroquímico que nadie firmó.
    //
    // Los valores se copian a locales porque Kotlin no hace smart cast sobre
    // propiedades declaradas en otro módulo: no puede garantizar que dos lecturas
    // devuelvan lo mismo si el otro módulo cambiara el campo por un getter.
    approval = approvalOf(approvedByName, approvedLicense, approvedAt),
)

private fun approvalOf(name: String?, license: String?, at: Long?): ProfessionalApproval? =
    if (name != null && license != null && at != null) {
        ProfessionalApproval(name, license, at)
    } else {
        null
    }

internal fun PestTaxon.toEntity(): PestEntity = PestEntity(
    id = id,
    scientificName = scientificName,
    commonName = commonName,
    localSynonymsJson = json.encodeToString(stringListSerializer, localSynonyms),
    crop = crop.name,
    affectedOrgansJson = json.encodeToString(stringListSerializer, affectedOrgans.map { it.name }),
    description = description,
    recommendationsJson = json.encodeToString(
        recommendationListSerializer,
        recommendations.map {
            RecommendationJson(
                kind = it.kind.name,
                summary = it.summary,
                detail = it.detail,
                activeIngredient = it.activeIngredient,
                dosage = it.dosage,
                preHarvestIntervalDays = it.preHarvestIntervalDays,
            )
        },
    ),
    imageRefsJson = json.encodeToString(stringListSerializer, imageRefs),
    version = version,
    kind = kind.name,
    reference = reference,
    approvedByName = approval?.agronomistName,
    approvedLicense = approval?.licenseNumber,
    approvedAt = approval?.approvedAtEpochMillis,
    updatedAt = System.currentTimeMillis(),
)

/** JSON corrupto degrada a lista vacía, nunca lanza: una ficha con los sinónimos
 *  ilegibles sigue siendo consultable, y hacer caer el catálogo entero por una
 *  fila mala dejaría al productor sin nada en el lote. */
private fun String.decodeStrings(): List<String> = runCatching {
    json.decodeFromString(stringListSerializer, this)
}.getOrDefault(emptyList())

private fun String.decodeRecommendations(): List<ManagementRecommendation> = runCatching {
    json.decodeFromString(recommendationListSerializer, this).map {
        ManagementRecommendation(
            kind = ManagementKind.entries.firstOrNull { kind -> kind.name == it.kind }
                ?: ManagementKind.MONITOREO,
            summary = it.summary,
            detail = it.detail,
            activeIngredient = it.activeIngredient,
            dosage = it.dosage,
            preHarvestIntervalDays = it.preHarvestIntervalDays,
        )
    }
}.getOrDefault(emptyList())
