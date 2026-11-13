package co.agrovision.network.api

import co.agrovision.core.common.AgroDispatcher
import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.Dispatcher
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.port.ArtifactFetcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Descarga de artefactos de modelo.
 *
 * Escribe al `OutputStream` que le pasan en lugar de devolver bytes: quien llama
 * lo envuelve en un `DigestOutputStream` y el SHA-256 se calcula **en flujo
 * durante la descarga** (§8.3, paso 3), sin cargar 9 MB en memoria para
 * hashearlos después.
 */
@Singleton
class OkHttpArtifactFetcher @Inject constructor(
    private val client: OkHttpClient,
    @Dispatcher(AgroDispatcher.IO) private val io: CoroutineDispatcher,
) : ArtifactFetcher {

    override suspend fun fetch(
        url: String,
        sink: OutputStream,
        maxBytes: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): AgroResult<Long, DomainError> = withContext(io) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AgroResult.failure(
                        DomainError.RemoteFailure(response.code, response.message)
                    )
                }
                val body = response.body
                    ?: return@withContext AgroResult.failure(
                        DomainError.RemoteFailure(response.code, "respuesta sin cuerpo")
                    )

                val total = body.contentLength()
                if (total > maxBytes) {
                    return@withContext AgroResult.failure(
                        DomainError.RemoteFailure(response.code, "el archivo supera el tamaño declarado")
                    )
                }
                var read = 0L
                val buffer = ByteArray(BUFFER_BYTES)

                body.byteStream().use { input ->
                    while (true) {
                        // La descarga puede durar minutos sobre 3G. Sin esto, un
                        // usuario que cancela deja la corrutina escribiendo hasta
                        // el final y gastando sus datos.
                        coroutineContext.ensureActive()

                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        // Sin Content-Length (transferencia por trozos) el tope se vigila aquí
                        if (read > maxBytes) {
                            return@withContext AgroResult.failure(
                                DomainError.RemoteFailure(response.code, "el archivo supera el tamaño declarado")
                            )
                        }
                        sink.write(buffer, 0, count)
                        onProgress(read, total)
                    }
                }
                sink.flush()
                AgroResult.success(read)
            }
        } catch (error: IOException) {
            AgroResult.failure(DomainError.NoConnection)
        }
    }

    override suspend fun fetchText(
        url: String,
        maxBytes: Long,
    ): AgroResult<String, DomainError> = withContext(io) {
        // Se leen enteros a memoria (hacen falta para la firma), pero con el mismo
        // tope en flujo que `fetch`: sin él, una respuesta enorme agota la memoria
        // antes de que la firma pueda rechazarla.
        val buffer = ByteArrayOutputStream()
        when (val result = fetch(url, buffer, maxBytes)) {
            is AgroResult.Failure -> result
            is AgroResult.Success -> AgroResult.success(buffer.toString(Charsets.UTF_8.name()))
        }
    }

    private companion object {
        const val BUFFER_BYTES = 16 * 1024
    }
}
