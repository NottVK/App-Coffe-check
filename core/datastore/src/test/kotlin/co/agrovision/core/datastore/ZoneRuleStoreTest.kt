package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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

    private val rule = ZoneRule("roya", 2, false, listOf(ZoneRuleRow(1, 10.0, 20.0, 0.0, 3.0)))

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
    fun `json corrupto se trata como sin regla`() = runTest {
        val preferences = MemoryPreferences()
        preferences.edit { it[stringPreferencesKey("zone_rule_json_roya")] = "{no es json" }

        assertNull(ZoneRuleStore(preferences).cached("roya"))
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
