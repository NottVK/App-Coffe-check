package co.agrovision.network.api

import android.util.Base64
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.common.Float16Codec
// `map` sobre AgroResult es una función de extensión de nivel superior, no un
// método: sin el import no está en el ámbito.
import co.agrovision.core.common.map
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.mlmodel.ModelChannel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.port.CatalogDelta
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.core.model.port.UploadOutcome
import co.agrovision.core.model.port.UploadStatus
import co.agrovision.core.model.review.ReviewRequest
import co.agrovision.network.api.dto.CaptureBatchRequestDto
import co.agrovision.network.api.dto.CaptureDto
import co.agrovision.network.api.dto.LocationDto
import co.agrovision.network.api.dto.ReviewImageDto
import co.agrovision.network.api.dto.ReviewRequestBatchDto
import co.agrovision.network.api.dto.ReviewRequestDto
import co.agrovision.network.api.dto.UploadResultDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación de la única frontera de red del dominio.
 *
 * Traduce entre entidades de dominio y DTO. Esa traducción no es burocracia: un
 * cambio de nombre de campo en el backend tiene que romper la compilación de este
 * archivo, no propagarse en silencio hasta una fila de Room.
 */
@Singleton
class RetrofitSyncGateway @Inject constructor(
    private val api: AgroVisionApiService,
    private val publicApi: PublicApiService,
    private val client: OkHttpClient,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : SyncGateway {

    override suspend fun uploadCaptures(
        batch: List<Capture>,
    ): AgroResult<List<UploadOutcome>, DomainError> = withContext(io) {
        request {
            api.uploadCaptures(
                // Clave de idempotencia del LOTE: el UUID de la primera captura,
                // que es estable entre reintentos porque el orden de la consulta
                // es determinista. Un UUID nuevo por intento haría que el
                // servidor tratara cada reintento como un lote distinto.
                idempotencyKey = batch.first().id,
                body = CaptureBatchRequestDto(batch.map { it.toDto() }),
            )
        }.map { body -> body.results.map { it.toDomain() } }
    }

    override suspend fun uploadReviewRequests(
        batch: List<ReviewRequest>,
    ): AgroResult<List<UploadOutcome>, DomainError> = withContext(io) {
        request {
            api.uploadReviewRequests(
                idempotencyKey = batch.first().id,
                body = ReviewRequestBatchDto(batch.map { it.toDto() }),
            )
        }.map { body -> body.results.map { it.toDomain() } }
    }

    /**
     * Sube el archivo a la URL prefirmada. **No pasa por la API** (ADR-010): las
     * imágenes van directas al almacenamiento de objetos para que el backend no
     * sea cuello de botella de ancho de banda.
     */
    override suspend fun uploadImage(
        presignedUrl: String,
        localPath: String,
    ): AgroResult<Unit, DomainError> = withContext(io) {
        val file = File(localPath)
        if (!file.isFile) {
            // El archivo desapareció —purga, limpieza del sistema, usuario—
            // pero la fila sigue. No es un fallo de red y reintentar no lo
            // arreglaría.
            return@withContext AgroResult.failure(
                DomainError.NotFound("imagen", localPath)
            )
        }

        try {
            val call = client.newCall(
                Request.Builder()
                    .url(presignedUrl)
                    .put(file.asRequestBody(JPEG.toMediaType()))
                    .build()
            )
            call.execute().use { response ->
                if (response.isSuccessful) AgroResult.unit
                else AgroResult.failure(
                    DomainError.RemoteFailure(response.code, response.message)
                )
            }
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }
    }

    override suspend fun fetchCatalogDelta(
        sinceEpochMillis: Long?,
        etag: String?,
    ): AgroResult<CatalogDelta, DomainError> = withContext(io) {
        try {
            val response = publicApi.syncCatalog(sinceIso = sinceEpochMillis?.toString(), etag = etag)

            // 304 no es un error: es el ETag funcionando y ahorrándole al
            // productor la descarga completa del catálogo.
            if (response.code() == NOT_MODIFIED) {
                return@withContext AgroResult.success(CatalogDelta.NotModified)
            }
            val body = response.body()
                ?: return@withContext AgroResult.failure(
                    DomainError.RemoteFailure(response.code(), response.message())
                )

            AgroResult.success(
                CatalogDelta.Changed(
                    upserted = body.upserted.map { it.toDomain() },
                    deletedIds = body.deletedIds,
                    etag = body.etag,
                    serverTimeEpochMillis = body.serverTime,
                )
            )
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }
    }

    override suspend fun fetchMyResolutions(
        sinceEpochMillis: Long?,
    ): AgroResult<List<ReviewRequest>, DomainError> = withContext(io) {
        // Pendiente: exige el mapeo de resolución a `ReviewRequest` completo, que
        // necesita releer la solicitud local para no perder sus imágenes. Se
        // resuelve en el repositorio, no aquí.
        AgroResult.success(emptyList())
    }

    override suspend fun fetchModelManifest(
        appVersion: String,
        deviceId: String,
    ): AgroResult<ModelManifest, DomainError> = withContext(io) {
        try {
            val response = publicApi.currentModel(appVersion = appVersion, deviceId = deviceId)
            val body = response.body()
            when {
                !response.isSuccessful ->
                    AgroResult.failure(
                        DomainError.RemoteFailure(response.code(), response.message())
                    )
                body == null ->
                    AgroResult.failure(DomainError.RemoteFailure(response.code(), "sin cuerpo"))
                else -> AgroResult.success(
                    ModelManifest(
                        version = body.version,
                        channel = ModelChannel.parse(body.channel),
                        minAppVersion = body.minAppVersion,
                        sizeBytes = body.sizeBytes,
                        sha256 = body.sha256,
                        signatureBase64 = body.signature,
                        modelUrl = body.artifacts.model,
                        labelsUrl = body.artifacts.labels,
                        calibrationUrl = body.artifacts.calibration,
                        releaseNotes = body.releaseNotes,
                        killSwitch = body.killSwitch,
                    )
                )
            }
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }
    }

    override suspend fun registerPushToken(token: String): AgroResult<Unit, DomainError> =
        withContext(io) {
            request {
                api.registerPushToken(
                    co.agrovision.network.api.dto.PushTokenDto(token, "")
                )
            }.map { }
        }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private inline fun <T> request(block: () -> Response<T>): AgroResult<T, DomainError> = try {
        val response = block()
        val body = response.body()
        when {
            !response.isSuccessful ->
                AgroResult.failure(DomainError.RemoteFailure(response.code(), response.message()))
            body == null && response.code() == NO_CONTENT ->
                @Suppress("UNCHECKED_CAST")
                AgroResult.success(Unit as T)
            body == null ->
                AgroResult.failure(DomainError.RemoteFailure(response.code(), "sin cuerpo"))
            else -> AgroResult.success(body)
        }
    } catch (error: IOException) {
        AgroResult.failure(DomainError.NoConnection)
    }

    private companion object {
        const val JPEG = "image/jpeg"
        const val NOT_MODIFIED = 304
        const val NO_CONTENT = 204
    }
}

// ── Mapeo dominio → DTO ──────────────────────────────────────────────────────

private fun Capture.toDto() = CaptureDto(
    id = id,
    capturedAt = capturedAtEpochMillis,
    modelVersion = modelVersion,
    predictedClassId = predictedClassId,
    calibratedConfidence = calibratedConfidence,
    oodScore = oodScore,
    gateOutcome = gateOutcome.name,
    // float16 en Base64: la mitad de bytes que float32, sin pérdida relevante
    // para la agrupación por similitud del servidor.
    embedding = embedding?.let {
        Base64.encodeToString(Float16Codec.encode(it.values), Base64.NO_WRAP)
    },
    userCorrectionClassId = userCorrectionClassId,
    userConfirmed = userConfirmed,
    cropType = cropType?.name,
    plantOrgan = plantOrgan?.name,
    location = location?.let { LocationDto(it.latitude, it.longitude, it.accuracyMeters) },
)

private fun ReviewRequest.toDto() = ReviewRequestDto(
    id = id,
    captureId = captureId,
    cropType = cropType.name,
    plantOrgan = plantOrgan.name,
    noteText = noteText,
    createdAt = createdAtEpochMillis,
    location = location?.let { LocationDto(it.latitude, it.longitude, it.accuracyMeters) },
    images = images.map { image ->
        ReviewImageDto(
            id = image.id,
            angle = image.angle.name,
            embedding = image.embedding?.let {
                Base64.encodeToString(Float16Codec.encode(it.values), Base64.NO_WRAP)
            },
        )
    },
)

private fun UploadResultDto.toDomain() = UploadOutcome(
    localId = id,
    serverId = serverId,
    status = when (status.lowercase()) {
        "accepted" -> UploadStatus.ACCEPTED
        // No es un error: es la idempotencia funcionando tras una respuesta
        // perdida. La fila local se marca como subida.
        "duplicate" -> UploadStatus.DUPLICATE
        else -> UploadStatus.REJECTED
    },
    presignedImageUrl = uploadUrl,
    rejectionReason = reason,
)

private fun co.agrovision.network.api.dto.PestDto.toDomain(): PestTaxon = PestTaxon(
    id = id,
    scientificName = scientificName,
    commonName = commonName,
    localSynonyms = localSynonyms,
    crop = co.agrovision.core.model.pest.CropType.entries
        .firstOrNull { it.name.equals(crop, ignoreCase = true) }
        ?: co.agrovision.core.model.pest.CropType.OTRO,
    affectedOrgans = affectedOrgans.mapNotNull { name ->
        co.agrovision.core.model.pest.PlantOrgan.entries
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
    },
    description = description,
    recommendations = recommendations.map {
        co.agrovision.core.model.pest.ManagementRecommendation(
            kind = co.agrovision.core.model.pest.ManagementKind.entries
                .firstOrNull { kind -> kind.name.equals(it.kind, ignoreCase = true) }
                ?: co.agrovision.core.model.pest.ManagementKind.MONITOREO,
            summary = it.summary,
            detail = it.detail,
            activeIngredient = it.activeIngredient,
            dosage = it.dosage,
            preHarvestIntervalDays = it.preHarvestIntervalDays,
        )
    },
    imageRefs = images,
    version = version,
    kind = co.agrovision.core.model.pest.PestKind.parse(kind),
    reference = reference?.takeIf { it.isNotBlank() },
    // Sin aval, la ficha se muestra sin sección de manejo (§13.6). El servidor
    // decide; el cliente solo respeta la ausencia.
    approval = approval?.let {
        co.agrovision.core.model.pest.ProfessionalApproval(
            agronomistName = it.agronomistName,
            licenseNumber = it.licenseNumber,
            approvedAtEpochMillis = it.approvedAt,
        )
    },
)
