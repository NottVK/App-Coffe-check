package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ZoneRuleStoreTest {

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private val rule = ZoneRule(
        "roya", 2, false,
        listOf(ZoneRuleRow(1, 10.0, 20.0, 0.0, 3.0), ZoneRuleRow(2, 100.0, 100.0, 3.0, 8.0)),
    )

    @Test
    fun `guarda regla y etag por tipo y los borra con 204`() = runTest {
        val store = ZoneRuleStore(MemoryPreferences())

        store.save("roya", rule, "\"abc\"")

        assertEquals(rule, store.cached("roya")!!.rule)
        assertEquals("\"abc\"", store.cached("roya")!!.etag)
        assertNull(store.cached("phoma"))
        store.clear("roya")
        assertNull(store.cached("roya"))
    }

    @Test
    fun `observe emite la regla, su reemplazo y su retirada, e ignora otras claves`() = runTest {
        val store = ZoneRuleStore(MemoryPreferences())
        store.observe("roya").test {
            assertNull(awaitItem())

            store.save("roya", rule, "\"a\"")
            assertEquals(rule, awaitItem()!!.rule)

            // Otra clave del mismo DataStore y otro tipo no reemiten.
            store.setPlantsPerHa(3300)
            store.save("phoma", rule.copy(tipoEnfermedad = "phoma"), null)
            expectNoEvents()

            val next = rule.copy(version = 3)
            store.save("roya", next, "\"b\"")
            assertEquals(next, awaitItem()!!.rule)

            // 204: la regla se retira.
            store.clear("roya")
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Un tipo con formato inválido nunca tiene regla.
        assertNull(store.observe("Tipo Inválido").first())
    }

    @Test
    fun `json corrupto se trata como sin regla`() = runTest {
        val preferences = MemoryPreferences()
        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = "{no es json" }

        assertNull(ZoneRuleStore(preferences).cached("roya"))
    }

    @Test
    fun `json legible pero invalido se trata como sin regla y no reutiliza su etag`() = runTest {
        val preferences = MemoryPreferences()
        val store = ZoneRuleStore(preferences)
        store.save("roya", rule, "\"abc\"")
        val valid = preferences.data.first()[stringPreferencesKey("zone_rule_json_roya")]!!

        // Guardado bajo `roya` pero declara `phoma`.
        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = valid.replace("\"roya\"", "\"phoma\"") }
        assertNull(store.cached("roya"))
        // Radio negativo.
        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = valid.replace("\"radioMinM\":0.0", "\"radioMinM\":-5.0") }
        assertNull(store.cached("roya"))
        // Mínimo mayor que máximo.
        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = valid.replace("\"radioMinM\":3.0", "\"radioMinM\":9.0") }
        assertNull(store.cached("roya"))

        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = valid }
        assertEquals("\"abc\"", store.cached("roya")!!.etag)
    }

    @Test
    fun `no escribe reglas invalidas ni de otro tipo`() = runTest {
        val store = ZoneRuleStore(MemoryPreferences())

        assertThrows<IllegalArgumentException> { store.save("phoma", rule, "\"abc\"") }
        assertThrows<IllegalArgumentException> {
            store.save("roya", rule.copy(filas = listOf(ZoneRuleRow(1, 100.0, 100.0, -1.0, 3.0))), null)
        }
        assertNull(store.cached("roya"))
        assertNull(store.cached("phoma"))
    }

    @Test
    fun `plantas por hectarea con valor por defecto y acotadas`() = runTest {
        val store = ZoneRuleStore(MemoryPreferences())
        assertEquals(5000, store.observePlantsPerHa().first())
        store.setPlantsPerHa(3300)
        assertEquals(3300, store.observePlantsPerHa().first())
        store.setPlantsPerHa(0)
        assertEquals(1, store.observePlantsPerHa().first())
    }
}
