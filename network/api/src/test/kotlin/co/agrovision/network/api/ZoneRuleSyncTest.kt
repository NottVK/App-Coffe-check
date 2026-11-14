package co.agrovision.network.api

import co.agrovision.core.model.port.CachedZoneRule
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        {"orden":2,"sevMax":30,"incMax":40,"radioMinM":3,"radioMaxM":8},
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

        repeat(5) { assertFalse(sync.sync("roya")) }
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
}
