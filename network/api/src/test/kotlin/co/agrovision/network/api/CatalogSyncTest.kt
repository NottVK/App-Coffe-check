package co.agrovision.network.api

import android.content.Context
import android.content.pm.ApplicationInfo
import co.agrovision.core.model.pest.PestTaxon
import co.agrovision.core.model.port.CatalogCursor
import co.agrovision.core.model.port.PestCatalogRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import retrofit2.Retrofit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class CatalogSyncTest {
    @Test fun `primer delta conserva ids e imagenes, es idempotente y 304 no escribe`() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("catalog-test").toFile()
        try {
            val seed = Json.parseToJsonElement(File("../../app/src/main/assets/catalog/pests.json").readText()).jsonArray
            assertEquals(8, seed.size)
            val rows = mutableMapOf<String, PestTaxon>()
            seed.forEach { element ->
                val o = element.jsonObject
                val dto = PestDto(o.getValue("id").jsonPrimitive.content, o.getValue("scientificName").jsonPrimitive.content,
                    o.getValue("commonName").jsonPrimitive.content, emptyList(), "CAFE", emptyList(), "", emptyList(),
                    emptyList(), 1, "ENFERMEDAD")
                rows[dto.id] = dto.toDomain(listOf("semilla.jpg"))
            }
            val repo = mockk<PestCatalogRepository>()
            var cursor = CatalogCursor(null, null)
            coEvery { repo.syncCursor() } answers { cursor }
            coEvery { repo.findById(any()) } answers { rows[firstArg()] }
            coEvery { repo.upsertAll(any()) } answers { firstArg<List<PestTaxon>>().forEach { rows[it.id] = it } }
            coEvery { repo.deleteAll(any()) } answers { firstArg<List<String>>().forEach { rows.remove(it) } }
            coEvery { repo.updateSyncCursor(any()) } answers { cursor = firstArg() }
            coEvery { repo.search("") } answers { rows.values.toList() }
            val context = mockk<Context>(); every { context.filesDir } returns dir
            every { context.applicationInfo } returns ApplicationInfo()
            val retrofit = Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val sync = CatalogSync(retrofit, repo, context, Dispatchers.Unconfined)
            val pests = seed.map { element ->
                val o = element.jsonObject
                buildJsonObject {
                    put("id", o.getValue("id")); put("scientificName", o.getValue("scientificName")); put("commonName", o.getValue("commonName"))
                    put("localSynonyms", JsonArray(emptyList())); put("crop", "CAFE"); put("affectedOrgans", JsonArray(emptyList()))
                    put("description", "actualizada"); put("recommendations", JsonArray(emptyList())); put("images", JsonArray(emptyList()))
                    put("version", 1); put("kind", "ENFERMEDAD")
                }
            }
            val body = buildJsonObject { put("upserted", JsonArray(pests)); put("deletedIds", JsonArray(emptyList())); put("etag", "\"v1\""); put("serverTime", 1700000000123L) }.toString()
            repeat(2) { server.enqueue(MockResponse().setBody(body).addHeader("ETag", "\"v1\"")) }
            server.enqueue(MockResponse().setResponseCode(304))
            assertTrue(sync.sync())
            val first = server.takeRequest(); assertEquals("/api/public/v1/pests/sync", first.path)
            assertEquals(seed.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet(), rows.keys)
            assertTrue(rows.values.all { it.imageRefs == listOf("semilla.jpg") })
            assertTrue(sync.sync())
            val second = server.takeRequest(); assertEquals("/api/public/v1/pests/sync?since=1700000000123", second.path)
            assertEquals("\"v1\"", second.getHeader("If-None-Match"))
            assertEquals(8, rows.size)
            assertTrue(sync.sync()); server.takeRequest()
            assertEquals(CatalogCursor(1700000000123L, "\"v1\""), cursor)
            coVerify(exactly = 2) { repo.upsertAll(any()) }
            coVerify(exactly = 2) { repo.updateSyncCursor(any()) }
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun `imagen nueva se descarga y un fallo conserva ficha y cursor para reintento`() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("catalog-merge").toFile()
        try {
            val old = PestDto("Enfermedad_Roya", "Roya", "Roya", emptyList(), "CAFE", emptyList(), "", emptyList(), emptyList(), 1, "Enfermedad").toDomain(listOf("original.jpg"))
            var row = old
            var cursor = CatalogCursor(null, null)
            val repo = mockk<PestCatalogRepository>()
            coEvery { repo.syncCursor() } answers { cursor }
            coEvery { repo.findById(any()) } answers { row }
            coEvery { repo.upsertAll(any()) } answers { row = firstArg<List<PestTaxon>>().single() }
            coEvery { repo.deleteAll(any()) } returns Unit
            coEvery { repo.updateSyncCursor(any()) } answers { cursor = firstArg() }
            coEvery { repo.search("") } answers { listOf(row) }
            val context = mockk<Context>(); every { context.filesDir } returns dir
            every { context.applicationInfo } returns ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
            val retrofit = Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
            val sync = CatalogSync(retrofit, repo, context, Dispatchers.Unconfined)
            val url = server.url("/foto").toString().replace("127.0.0.1", "localhost")
            val body = """{"upserted":[{"id":"Enfermedad_Roya","scientificName":"Roya","commonName":"Roya","localSynonyms":[],"crop":"CAFE","affectedOrgans":[],"description":"nueva","recommendations":[],"images":["$url"],"version":2,"kind":"Enfermedad"}],"deletedIds":[],"etag":"v2","serverTime":42}"""
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setResponseCode(500))
            assertFalse(sync.sync())
            assertEquals(old, row); assertEquals(CatalogCursor(null, null), cursor)
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))))
            assertTrue(sync.sync())
            assertTrue(File(row.imageRefs.single()).isFile)
            assertEquals("Enfermedad_Roya", row.id); assertEquals(CatalogCursor(42, "v2"), cursor)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun `descargador valida politica tipo firma y tamaño y reutiliza URL`() {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("catalog-images").toFile()
        try {
            val url = server.url("/imagen").toString().replace("127.0.0.1", "localhost")
            assertNull(CatalogImageDownloader(dir, false).download(url))
            val downloader = CatalogImageDownloader(dir, true)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("no"))
            assertNull(downloader.download(url))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("firma falsa"))
            assertNull(downloader.download(url))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
            assertNull(downloader.download(url))
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            // Cabecera válida pero truncada (sin chunk IEND): no se acepta ni se cachea.
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
            assertNull(downloader.download(url))
            val iend = byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4e, 0x44, 0xae.toByte(), 0x42, 0x60, 0x82.toByte())
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png + iend)))
            val path = downloader.download(url)
            assertNotNull(path); assertEquals(path, downloader.download(url)); assertEquals(5, server.requestCount)
            // Un archivo corrupto ya en caché se descarta y se vuelve a descargar.
            File(path!!).writeBytes(png)
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png + iend)))
            assertEquals(path, downloader.download(url)); assertEquals(6, server.requestCount)
            downloader.clean(emptySet()); assertFalse(File(path).exists())
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
