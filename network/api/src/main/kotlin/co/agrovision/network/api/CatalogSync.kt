package co.agrovision.network.api

import android.content.Context
import android.content.pm.ApplicationInfo
import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.pest.*
import co.agrovision.core.model.port.CatalogCursor
import co.agrovision.core.model.port.PestCatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal interface CatalogApi {
    @GET("public/v1/pests/sync")
    suspend fun sync(@Query("since") since: Long?, @Header("If-None-Match") etag: String?): Response<CatalogDeltaDto>
}

@Serializable
internal data class CatalogDeltaDto(val upserted: List<PestDto>, val deletedIds: List<String>, val etag: String, val serverTime: Long)
@Serializable
internal data class PestDto(
    val id: String, val scientificName: String, val commonName: String,
    val localSynonyms: List<String>, val crop: String, val affectedOrgans: List<String>,
    val description: String, val recommendations: List<RecommendationDto>, val images: List<String>,
    val version: Int, val kind: String, val reference: String? = null, val approval: ApprovalDto? = null,
) {
    fun toDomain(refs: List<String>) = PestTaxon(
        id, scientificName, commonName, localSynonyms,
        CropType.entries.firstOrNull { it.name.equals(crop, true) } ?: CropType.OTRO,
        affectedOrgans.mapNotNull { value -> PlantOrgan.entries.firstOrNull { it.name.equals(value, true) } },
        description, recommendations.map { it.toDomain() }, refs, version, PestKind.parse(kind), reference,
        approval?.let { ProfessionalApproval(it.agronomistName, it.licenseNumber, it.approvedAt) },
    )
}
@Serializable
internal data class RecommendationDto(val kind: String, val summary: String, val detail: String, val activeIngredient: String? = null, val dosage: String? = null, val preHarvestIntervalDays: Int? = null) {
    fun toDomain() = ManagementRecommendation(ManagementKind.entries.firstOrNull { it.name.equals(kind, true) } ?: ManagementKind.MONITOREO, summary, detail, activeIngredient, dosage, preHarvestIntervalDays)
}
@Serializable
internal data class ApprovalDto(val agronomistName: String, val licenseNumber: String, val approvedAt: Long)

/** Ejecutado únicamente por SyncWorker, bajo las restricciones de red de WorkManager. */
@Singleton
class CatalogSync @Inject constructor(
    retrofit: Retrofit,
    private val catalog: PestCatalogRepository,
    @ApplicationContext context: Context,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val api = retrofit.create(CatalogApi::class.java)
    private val images = CatalogImageDownloader(File(context.filesDir, "catalog-images"),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    suspend fun sync(): Boolean = withContext(io) {
        try {
            val cursor = catalog.syncCursor()
            val response = api.sync(cursor.sinceEpochMillis, cursor.etag)
            if (response.code() == 304) return@withContext true
            val delta = response.body()
            if (!response.isSuccessful || delta == null) return@withContext false
            val merged = mutableListOf<PestTaxon>()
            for (pest in delta.upserted) {
                // Las descargas son bloqueantes: entre ficha y ficha se atiende la cancelación del worker.
                coroutineContext.ensureActive()
                val old = catalog.findById(pest.id)?.imageRefs.orEmpty()
                val refs = if (pest.images.isEmpty()) old else {
                    // Tope por ficha: un delta no puede llenar el almacenamiento a fuerza de URLs.
                    val downloaded = pest.images.take(MAX_IMAGES_PER_PEST).map { images.download(it) }
                    // No avanzar el cursor: el servidor debe reenviar el mismo delta.
                    if (downloaded.any { it == null }) return@withContext false
                    downloaded.filterNotNull()
                }
                merged += pest.toDomain(refs)
            }
            catalog.upsertAll(merged)
            catalog.deleteAll(delta.deletedIds)
            catalog.updateSyncCursor(CatalogCursor(delta.serverTime, response.headers()["ETag"] ?: delta.etag))
            images.clean(catalog.search("").flatMap { it.imageRefs }.toSet())
            true
        } catch (_: IOException) { false
        } catch (_: kotlinx.serialization.SerializationException) { false
        } catch (_: IllegalArgumentException) { false }
    }

    private companion object { const val MAX_IMAGES_PER_PEST = 10 }
}

/** Cliente público dedicado, sin interceptores ni redirecciones automáticas. `callTimeout` acota la
 *  descarga completa: un servidor que gotea bytes no retiene el worker indefinidamente. */
internal class CatalogImageDownloader(private val directory: File, private val allowLocalHttp: Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()) {
    fun download(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" && !(allowLocalHttp && url.scheme == "http" && url.host in setOf("localhost", "10.0.2.2"))) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        for (ext in listOf("jpg", "png", "webp")) {
            val existing = File(directory, "$hash.$ext")
            // Una imagen truncada en caché (de antes de esta validación) se descarta y se vuelve a pedir.
            if (existing.isFile) { if (isComplete(ext, existing)) return existing.absolutePath else existing.delete() }
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) return null
        var temp: File? = null
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: return null
                val ext = when (type.lowercase()) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> return null }
                if (body.contentLength() > MAX_BYTES) return null
                temp = File.createTempFile("catalog-", ".tmp", directory)
                val file = temp!!
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_BYTES) return null; output.write(buffer, 0, n) }
                } }
                val signature = file.inputStream().use { input -> ByteArray(12).let { bytes -> val n = input.read(bytes); bytes.copyOf(n.coerceAtLeast(0)) } }
                if (!validSignature(ext, signature) || !isComplete(ext, file)) return null
                val target = File(directory, "$hash.$ext")
                if (!file.renameTo(target)) return null
                return target.absolutePath
            }
        } catch (_: IOException) { return null } finally { temp?.delete() }
    }
    fun clean(references: Set<String>) {
        directory.listFiles()?.filter { it.isFile && it.absolutePath !in references }?.forEach { it.delete() }
    }
    private fun validSignature(ext: String, b: ByteArray): Boolean = when (ext) {
        "jpg" -> b.size >= 3 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()
        "png" -> b.size >= 8 && b.take(8) == listOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map { it.toByte() }
        "webp" -> b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
        else -> false
    }
    /** La cabecera sola admite archivos truncados; se exige también el cierre del formato. */
    private fun isComplete(ext: String, file: File): Boolean {
        val length = file.length()
        if (length < 12) return ext == "jpg" && length >= 4 && tail(file, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        return when (ext) {
            "jpg" -> tail(file, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
            "png" -> tail(file, 8).contentEquals(byteArrayOf(0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte()))
            "webp" -> java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(4); val b = ByteArray(4); raf.readFully(b)
                val riffSize = (b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) or
                    ((b[2].toLong() and 255) shl 16) or ((b[3].toLong() and 255) shl 24)
                riffSize + 8 == length
            }
            else -> false
        }
    }
    private fun tail(file: File, n: Int): ByteArray = java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(file.length() - n); ByteArray(n).also { raf.readFully(it) }
    }
    companion object { private const val MAX_BYTES = 5L * 1024 * 1024; private const val CALL_TIMEOUT_SECONDS = 120L }
}
