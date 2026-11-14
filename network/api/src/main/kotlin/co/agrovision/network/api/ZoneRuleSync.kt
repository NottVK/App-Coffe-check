package co.agrovision.network.api

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import co.agrovision.core.model.zone.ZoneRuleTypes
import co.agrovision.core.model.zone.isValidFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import retrofit2.Retrofit
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

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
 * Ejecutado únicamente por `ZoneRuleWorker`, un trabajo propio: la cola del
 * productor no espera a las reglas. Un fallo aquí no toca la caché existente:
 * la app sigue usando la última regla recibida. Solo un 204 (el servidor ya no
 * tiene regla activa) borra la caché del tipo, para no seguir ofreciendo una
 * regla retirada.
 *
 * ## Por qué OkHttp a mano y no Retrofit
 *
 * Retrofit copia **entero** a memoria el cuerpo de toda respuesta no exitosa
 * (`Utils.buffer`), incluso con `@Streaming`: un 500 con un cuerpo enorme se
 * saltaba el tope de [MAX_BODY_BYTES]. Aquí el cuerpo solo se lee en un 200 y
 * con tope; en cualquier otro código se cierra sin leer un byte. El tope cuenta
 * bytes ya descomprimidos, así que un gzip pequeño que se expande tampoco pasa.
 *
 * El cliente deriva del compartido (`newBuilder`): conserva el pinning, los
 * interceptores y el pool de conexiones.
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
    private val client = (retrofit.callFactory() as OkHttpClient).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // Unos pocos KB: lo que no llega en este plazo es un fallo, no una espera.
        // `callTimeout` cubre la llamada entera, lectura del cuerpo incluida.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val endpoint = requireNotNull(retrofit.baseUrl().resolve(PATH)) { "URL base inválida" }
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `true` si todos los tipos quedaron al día (200, 204, o 304 con regla válida en caché).
     *
     * Con presupuesto **total**: al agotarse [budgetMillis] se cancela la llamada
     * en vuelo (conexión y cuerpo) y los tipos que falten quedan para el próximo
     * intento, que los pide con su ETag.
     */
    suspend fun syncAll(
        types: List<String> = ZoneRuleTypes.ALL,
        budgetMillis: Long = TOTAL_BUDGET_MILLIS,
    ): Boolean = withTimeoutOrNull(budgetMillis) {
        var allOk = true
        for (type in types) {
            if (!sync(type)) allOk = false
        }
        allOk
    } ?: false

    suspend fun sync(type: String): Boolean = withContext(io) {
        try {
            // `cached` ya descarta reglas inválidas: su ETag no se reutiliza.
            val etag = rules.cached(type)?.etag
            when (val fetched = fetch(type, etag)) {
                // Solo es «al día» si pedimos con un ETag de una regla válida; un 304
                // sin él (servidor o proxy erróneo) deja al tipo sin regla: fallo.
                Fetched.NotModified -> etag != null
                Fetched.NoRule -> { rules.clear(type); true }
                // 400 (tipo inválido), 3xx, 5xx, red o cuerpo excesivo: se conserva la caché.
                Fetched.Failed -> false
                is Fetched.Body -> {
                    val rule = json.decodeFromString(ZoneRuleDto.serializer(), fetched.raw).toDomainOrNull(type)
                        ?: return@withContext false
                    rules.save(type, rule, fetched.etag)
                    true
                }
            }
        } catch (_: IOException) { false
        } catch (_: SerializationException) { false
        } catch (_: IllegalArgumentException) { false }
    }

    private sealed interface Fetched {
        data object NotModified : Fetched
        data object NoRule : Fetched
        data object Failed : Fetched
        data class Body(val raw: String, val etag: String?) : Fetched
    }

    /**
     * Petición cancelable. El cuerpo se lee dentro del callback de OkHttp, así que
     * cancelar la corrutina (presupuesto agotado, worker detenido) llama a
     * `Call.cancel()`, que corta también una lectura de cuerpo a medias.
     */
    private suspend fun fetch(type: String, etag: String?): Fetched = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(endpoint.newBuilder().addQueryParameter("tipo", type).build())
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(Fetched.Failed)
            }

            override fun onResponse(call: Call, response: Response) {
                val fetched = try {
                    response.use(::read)
                } catch (_: IOException) {
                    Fetched.Failed
                }
                continuation.resume(fetched)
            }
        })
    }

    /** Solo un 200 lee cuerpo; `use` cierra los demás sin consumirlos. */
    private fun read(response: Response): Fetched = when (response.code) {
        304 -> Fetched.NotModified
        204 -> Fetched.NoRule
        200 -> response.body?.let(::readLimited)?.let { Fetched.Body(it, response.header("ETag")) } ?: Fetched.Failed
        else -> Fetched.Failed
    }

    /**
     * Lee como mucho [MAX_BODY_BYTES]: una regla tiene a lo sumo 12 filas, unos pocos KB.
     * Nunca pide a la fuente más de un byte por encima del tope.
     */
    private fun readLimited(body: ResponseBody): String? {
        if (body.contentLength() > MAX_BODY_BYTES) return null
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size <= MAX_BODY_BYTES) {
            if (source.read(buffer, MAX_BODY_BYTES + 1 - buffer.size) == -1L) return buffer.readUtf8()
        }
        return null
    }

    internal companion object {
        const val MAX_BODY_BYTES = 64L * 1024
        const val PATH = "public/v1/zone-rules"
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 20L

        /** Seis tipos de unos pocos KB: de sobra con red lenta, y muy por debajo de los 10 min de un worker. */
        const val TOTAL_BUDGET_MILLIS = 60_000L
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
