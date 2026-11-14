package co.agrovision.network.api

import co.agrovision.core.model.port.CachedZoneRule
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZoneRuleSyncTest {

    private class MemoryRules : ZoneRuleRepository {
        val rows = mutableMapOf<String, CachedZoneRule>()
        var saves = 0
        override suspend fun cached(type: String) = rows[type]
        override fun observe(type: String): Flow<CachedZoneRule?> = kotlinx.coroutines.flow.flow { emit(rows[type]) }
        override suspend fun save(type: String, rule: ZoneRule, etag: String?) { saves++; rows[type] = CachedZoneRule(rule, etag) }
        override suspend fun clear(type: String) { rows.remove(type) }
        private val plants = MutableStateFlow(5000)
        override fun observePlantsPerHa(): Flow<Int> = plants
        override suspend fun setPlantsPerHa(value: Int) { plants.value = value }
    }

    private lateinit var server: MockWebServer
    private val rules = MemoryRules()
    private lateinit var sync: ZoneRuleSync

    @BeforeEach fun setUp() {
        server = MockWebServer(); server.start()
        val retrofit = retrofit2.Retrofit.Builder().baseUrl(server.url("/api/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build()
        sync = ZoneRuleSync(retrofit, rules, Dispatchers.Unconfined)
    }

    @AfterEach fun tearDown() { server.shutdown() }

    private val body = """{"tipoEnfermedad":"roya","version":2,"calibrada":false,"filas":[
        {"orden":2,"sevMax":100,"incMax":100,"radioMinM":3,"radioMaxM":8},
        {"orden":1,"sevMax":10,"incMax":20,"radioMinM":0,"radioMaxM":3}]}"""

    @Test fun `200 guarda con etag, 304 no escribe y envia If-None-Match`() = runBlocking {
        server.enqueue(MockResponse().setBody(body).addHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(304))

        assertTrue(sync.sync("roya"))
        val first = server.takeRequest()
        assertEquals("/api/public/v1/zone-rules?tipo=roya", first.path)
        assertNull(first.getHeader("If-None-Match"))
        val cached = rules.rows.getValue("roya")
        assertEquals("\"e1\"", cached.etag)
        assertEquals(listOf(1, 2), cached.rule.filas.map { it.orden })
        assertEquals(ZoneRuleRow(1, 10.0, 20.0, 0.0, 3.0), cached.rule.filas.first())

        assertTrue(sync.sync("roya"))
        assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, rules.saves)
    }

    @Test fun `204 borra la regla en cache`() = runBlocking {
        rules.rows["roya"] = CachedZoneRule(ZoneRule("roya", 1, false, listOf(ZoneRuleRow(1, 100.0, 100.0, 0.0, 1.0))), "\"x\"")
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(sync.sync("roya"))
        assertNull(rules.rows["roya"])
    }

    @Test fun `400, 500 y cuerpo invalido conservan la cache`() = runBlocking {
        val old = CachedZoneRule(ZoneRule("roya", 1, false, listOf(ZoneRuleRow(1, 100.0, 100.0, 0.0, 1.0))), "\"x\"")
        rules.rows["roya"] = old
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"Falta el parámetro tipo."}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("no es json"))
        // Tipo distinto al pedido.
        server.enqueue(MockResponse().setBody(body.replace("\"roya\"", "\"phoma\"")))
        // Radio mínimo mayor que el máximo.
        server.enqueue(MockResponse().setBody(body.replace("\"radioMinM\":3", "\"radioMinM\":9")))
        // Más daño, menos zona: radios decrecientes entre filas.
        server.enqueue(MockResponse().setBody(body.replace("\"radioMinM\":3,\"radioMaxM\":8", "\"radioMinM\":0,\"radioMaxM\":1")))
        // Última fila sin cobertura 100/100.
        server.enqueue(MockResponse().setBody(body.replace("\"sevMax\":100", "\"sevMax\":90")))
        // Orden no consecutivo desde 1.
        server.enqueue(MockResponse().setBody(body.replace("\"orden\":2", "\"orden\":3")))
        // Radio por encima de los 500 m del contrato.
        server.enqueue(MockResponse().setBody(body.replace("\"radioMaxM\":8", "\"radioMaxM\":501")))

        repeat(9) { assertFalse(sync.sync("roya")) }
        assertEquals(old, rules.rows["roya"])
    }

    @Test fun `cuerpo mayor a 64 KB se descarta`() = runBlocking {
        val padding = " ".repeat(ZoneRuleSync.MAX_BODY_BYTES.toInt() + 1)
        server.enqueue(MockResponse().setBody(body + padding))
        assertFalse(sync.sync("roya"))
        // Sin Content-Length (chunked) también se corta al leer.
        server.enqueue(MockResponse().setChunkedBody(body + padding, 4096))
        assertFalse(sync.sync("roya"))
        assertNull(rules.rows["roya"])
    }

    @Test fun `syncAll consulta los seis tipos y un fallo no detiene a los demas`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        repeat(5) { server.enqueue(MockResponse().setResponseCode(204)) }

        assertFalse(sync.syncAll())
        val paths = List(6) { server.takeRequest().path }
        assertEquals(
            listOf("roya", "cercospora", "phoma", "antracnosis", "minador", "hormigas").map { "/api/public/v1/zone-rules?tipo=$it" },
            paths,
        )
    }

    @Test fun `304 sin regla valida en cache no cuenta como exito`() = runBlocking {
        // Sin caché no se envía ETag: un 304 es una respuesta errónea del servidor o un proxy.
        server.enqueue(MockResponse().setResponseCode(304))
        assertFalse(sync.sync("roya"))
        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertNull(rules.rows["roya"])
    }

    @Test fun `no sigue redirecciones a otro host y conserva la cache`() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            val old = CachedZoneRule(ZoneRule("roya", 1, false, listOf(ZoneRuleRow(1, 100.0, 100.0, 0.0, 1.0))), "\"x\"")
            rules.rows["roya"] = old
            other.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/api/public/v1/zone-rules?tipo=roya")))

            assertFalse(sync.sync("roya"))
            assertEquals(0, other.requestCount)
            assertEquals(old, rules.rows["roya"])
        } finally {
            other.shutdown()
        }
    }

    // ── Respuestas de error enormes (#1) ─────────────────────────────────────
    //
    // El cuerpo se sirve a 16 KB cada 250 ms: bajarlo entero (lo que hacía Retrofit
    // con toda respuesta no exitosa) tardaría más de un minuto. Si `sync` vuelve
    // enseguida es que lo cerró sin leerlo.

    private val oldRule = CachedZoneRule(ZoneRule("roya", 1, false, listOf(ZoneRuleRow(1, 100.0, 100.0, 0.0, 1.0))), "\"x\"")
    private val hugeBytes = 4 * 1024 * 1024

    private fun MockResponse.slow() = throttleBody(16 * 1024, 250, java.util.concurrent.TimeUnit.MILLISECONDS)

    private fun assertFailsFastKeepingCache(response: MockResponse) = runBlocking {
        rules.rows["roya"] = oldRule
        server.enqueue(response.slow())
        val started = System.nanoTime()

        assertFalse(sync.sync("roya"))

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 10_000, "tardó $elapsedMs ms: el cuerpo de error se descargó")
        assertEquals(oldRule, rules.rows["roya"])
    }

    private fun gzip(bytes: ByteArray): okio.Buffer {
        val out = okio.Buffer()
        java.util.zip.GZIPOutputStream(out.outputStream()).use { it.write(bytes) }
        return out
    }

    @Test fun `un 500 enorme con Content-Length no se descarga`() {
        val response = MockResponse().setResponseCode(500).setBody("x".repeat(hugeBytes))
        assertEquals(hugeBytes.toString(), response.headers["Content-Length"])
        assertFailsFastKeepingCache(response)
    }

    @Test fun `un 500 enorme chunked no se descarga`() {
        val response = MockResponse().setResponseCode(500).setChunkedBody("x".repeat(hugeBytes), 8192)
        assertNull(response.headers["Content-Length"])
        assertFailsFastKeepingCache(response)
    }

    @Test fun `un 500 enorme con gzip no se descarga ni se descomprime`() {
        // Bytes aleatorios: no comprimen, así que también pesan en la red.
        val compressed = gzip(java.util.Random(7).let { r -> ByteArray(hugeBytes).also(r::nextBytes) })
        assertTrue(compressed.size > hugeBytes / 2)
        assertFailsFastKeepingCache(
            MockResponse().setResponseCode(500).addHeader("Content-Encoding", "gzip").setBody(compressed),
        )
        assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test fun `un 400 enorme tampoco se descarga`() =
        assertFailsFastKeepingCache(MockResponse().setResponseCode(400).setBody("x".repeat(hugeBytes)))

    @Test fun `un 200 gzip que se expande por encima del tope se descarta`() = runBlocking {
        // Pocos KB en la red, 8 MB descomprimidos: el tope cuenta bytes ya expandidos.
        val bomb = gzip((body + " ".repeat(8 * 1024 * 1024)).toByteArray())
        assertTrue(bomb.size < ZoneRuleSync.MAX_BODY_BYTES)
        server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody(bomb))
        assertFalse(sync.sync("roya"))
        assertNull(rules.rows["roya"])

        // Y un gzip dentro del tope sí se acepta.
        server.enqueue(MockResponse().addHeader("Content-Encoding", "gzip").setBody(gzip(body.toByteArray())).addHeader("ETag", "\"g\""))
        assertTrue(sync.sync("roya"))
        assertEquals("\"g\"", rules.rows.getValue("roya").etag)
    }

    @Test fun `un cuerpo justo en el tope se acepta y un byte mas no`() = runBlocking {
        val exact = body + " ".repeat(ZoneRuleSync.MAX_BODY_BYTES.toInt() - body.toByteArray().size)
        server.enqueue(MockResponse().setChunkedBody(exact, 4096))
        assertTrue(sync.sync("roya"))
        rules.rows.clear()
        server.enqueue(MockResponse().setChunkedBody("$exact ", 4096))
        assertFalse(sync.sync("roya"))
        assertNull(rules.rows["roya"])
    }

    // ── Presupuesto total y cancelación (#2) ─────────────────────────────────

    @Test fun `syncAll respeta el presupuesto total y cancela la llamada en vuelo`() = runBlocking {
        rules.rows["roya"] = oldRule
        // El servidor acepta la petición y no responde nunca.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()

        assertFalse(sync.syncAll(budgetMillis = 400))

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 5_000, "tardó $elapsedMs ms: el presupuesto no cortó la llamada")
        // Solo salió la primera petición: los demás tipos no se consultaron.
        assertEquals(1, server.requestCount)
        assertEquals(oldRule, rules.rows["roya"])
    }

    @Test fun `el presupuesto corta tambien un cuerpo 200 que llega a cuentagotas`() = runBlocking {
        server.enqueue(MockResponse().setBody(body).addHeader("ETag", "\"lento\"").throttleBody(8, 1, java.util.concurrent.TimeUnit.SECONDS))
        val started = System.nanoTime()

        assertFalse(sync.syncAll(types = listOf("roya"), budgetMillis = 400))

        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertNull(rules.rows["roya"])
    }

    @Test fun `cancelar la corrutina cancela la llamada HTTP`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { sync.sync("roya") }
        server.takeRequest()
        val started = System.nanoTime()

        job.cancelAndJoin()

        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertTrue(job.isCancelled)
    }

    @Test fun `dentro del presupuesto syncAll sigue siendo exitoso`() = runBlocking {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertTrue(sync.syncAll())
    }
}
