package co.agrovision.network.api

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import co.agrovision.core.model.zone.ZoneRuleTypes
import co.agrovision.core.model.zone.isValidFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal interface ZoneRuleApi {
    /** Público, sin JWT. `@Streaming`: el cuerpo se lee a mano con tope de tamaño. */
    @Streaming
    @GET("public/v1/zone-rules")
    suspend fun activeRule(@Query("tipo") type: String, @Header("If-None-Match") etag: String?): Response<ResponseBody>
}

@Serializable
internal data class ZoneRuleDto(
    val tipoEnfermedad: String,
    val version: Int,
    val calibrada: Boolean,
    val filas: List<ZoneRuleRowDto>,
)

@Serializable
internal data class ZoneRuleRowDto(
    val orden: Int,
    val sevMax: Double,
    val incMax: Double,
    val radioMinM: Double,
    val radioMaxM: Double,
)

/**
 * Descarga de las reglas de «zona a vigilar», una petición por tipo con ETag.
 *
 * Ejecutado únicamente por SyncWorker, **después** de la cola del productor y del
 * catálogo. Un fallo aquí no toca la caché existente: la app sigue usando la
 * última regla recibida. Solo un 204 (el servidor ya no tiene regla activa)
 * borra la caché del tipo, para no seguir ofreciendo una regla retirada.
 *
 * Sin redirecciones: el pinning de OkHttp se aplica por hostname, así que un 3xx
 * HTTPS→HTTPS hacia otro host podría servir una regla (o un 204) desde un
 * destino sin pines. Un 3xx aquí es un fallo más y se conserva la caché.
 */
@Singleton
class ZoneRuleSync @Inject constructor(
    retrofit: Retrofit,
    private val rules: ZoneRuleRepository,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val api = retrofit.newBuilder()
        .client((retrofit.callFactory() as OkHttpClient).newBuilder().followRedirects(false).build())
        .build()
        .create(ZoneRuleApi::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** `true` si todos los tipos quedaron al día (200, 204, o 304 con regla válida en caché). */
    suspend fun syncAll(types: List<String> = ZoneRuleTypes.ALL): Boolean {
        var allOk = true
        for (type in types) {
            currentCoroutineContext().ensureActive()
            if (!sync(type)) allOk = false
        }
        return allOk
    }

    suspend fun sync(type: String): Boolean = withContext(io) {
        try {
            // `cached` ya descarta reglas inválidas: su ETag no se reutiliza.
            val etag = rules.cached(type)?.etag
            val response = api.activeRule(type, etag)
            response.errorBody()?.close()
            when (response.code()) {
                // Solo es «al día» si pedimos con un ETag de una regla válida; un 304
                // sin él (servidor o proxy erróneo) deja al tipo sin regla: fallo.
                304 -> { response.body()?.close(); etag != null }
                204 -> { response.body()?.close(); rules.clear(type); true }
                200 -> {
                    val raw = response.body()?.use { readLimited(it) } ?: return@withContext false
                    val rule = json.decodeFromString(ZoneRuleDto.serializer(), raw).toDomainOrNull(type)
                        ?: return@withContext false
                    rules.save(type, rule, response.headers()["ETag"])
                    true
                }
                // 400 (tipo inválido) y demás: no se reintenta en bucle; se conserva la caché.
                else -> { response.body()?.close(); false }
            }
        } catch (_: IOException) { false
        } catch (_: SerializationException) { false
        } catch (_: IllegalArgumentException) { false }
    }

    /** Lee como mucho [MAX_BODY_BYTES]: una regla tiene a lo sumo 12 filas, unos pocos KB. */
    private fun readLimited(body: ResponseBody): String? {
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, 8192)
            if (read == -1L) break
            if (buffer.size > MAX_BODY_BYTES) return null
        }
        return buffer.readUtf8()
    }

    internal companion object {
        const val MAX_BODY_BYTES = 64L * 1024
    }

    /**
     * Validación defensiva con la misma regla de dominio que la caché
     * ([isValidFor]): una tabla incoherente no se guarda, se conserva la anterior.
     */
    private fun ZoneRuleDto.toDomainOrNull(requested: String): ZoneRule? = ZoneRule(
        tipoEnfermedad, version, calibrada,
        filas.sortedBy { it.orden }.map { ZoneRuleRow(it.orden, it.sevMax, it.incMax, it.radioMinM, it.radioMaxM) },
    ).takeIf { it.isValidFor(requested) }
}
