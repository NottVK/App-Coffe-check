package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.agrovision.core.model.port.CachedZoneRule
import co.agrovision.core.model.port.ZoneRuleRepository
import co.agrovision.core.model.zone.ZoneDefaults
import co.agrovision.core.model.zone.ZoneRule
import co.agrovision.core.model.zone.ZoneRuleRow
import co.agrovision.core.model.zone.isValidFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caché de reglas de zona en Preferences DataStore: JSON + ETag por tipo.
 *
 * Va aquí y no en Room a propósito: son seis documentos pequeños que se
 * reemplazan enteros, y meterlos en Room exigiría una migración que chocaría con
 * la de `owner_id` pendiente.
 *
 * ⚠️ Las claves son parte del contrato de identidad, como las de [PreferencesStore].
 */
@Singleton
class ZoneRuleStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : ZoneRuleRepository {

    override suspend fun cached(type: String): CachedZoneRule? {
        if (!TYPE_PATTERN.matches(type)) return null
        val preferences = store.data.first()
        val raw = preferences[ruleKey(type)] ?: return null
        // Un JSON ilegible (versión vieja, corrupción) se trata como «sin regla»:
        // mejor no mostrar zona que mostrar una inventada.
        val rule = try {
            json.decodeFromString(StoredZoneRule.serializer(), raw).toDomain()
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        // JSON legible pero semánticamente corrupto (otro tipo, radios negativos,
        // tabla incoherente): también «sin regla», y sin devolver su ETag, para
        // que la siguiente descarga sea incondicional y lo reemplace.
        if (!rule.isValidFor(type)) return null
        return CachedZoneRule(rule, preferences[etagKey(type)])
    }

    override suspend fun save(type: String, rule: ZoneRule, etag: String?) {
        require(TYPE_PATTERN.matches(type)) { "Tipo de regla inválido" }
        require(rule.isValidFor(type)) { "Regla de zona inválida para $type" }
        val encoded = json.encodeToString(StoredZoneRule.serializer(), StoredZoneRule.from(rule))
        store.edit { preferences ->
            preferences[ruleKey(type)] = encoded
            if (etag != null) preferences[etagKey(type)] = etag else preferences.remove(etagKey(type))
        }
    }

    override suspend fun clear(type: String) {
        if (!TYPE_PATTERN.matches(type)) return
        store.edit { preferences ->
            preferences.remove(ruleKey(type))
            preferences.remove(etagKey(type))
        }
    }

    override fun observePlantsPerHa(): Flow<Int> = store.data.map { preferences ->
        preferences[PLANTS_PER_HA]
            ?.takeIf { it in ZoneDefaults.MIN_PLANTS_PER_HA..ZoneDefaults.MAX_PLANTS_PER_HA }
            ?: ZoneDefaults.PLANTS_PER_HA
    }

    override suspend fun setPlantsPerHa(value: Int) {
        store.edit { preferences ->
            preferences[PLANTS_PER_HA] = value.coerceIn(ZoneDefaults.MIN_PLANTS_PER_HA, ZoneDefaults.MAX_PLANTS_PER_HA)
        }
    }

    @Serializable
    private data class StoredZoneRule(
        val tipoEnfermedad: String,
        val version: Int,
        val calibrada: Boolean,
        val filas: List<StoredRow>,
    ) {
        fun toDomain() = ZoneRule(
            tipoEnfermedad, version, calibrada,
            filas.map { ZoneRuleRow(it.orden, it.sevMax, it.incMax, it.radioMinM, it.radioMaxM) },
        )

        companion object {
            fun from(rule: ZoneRule) = StoredZoneRule(
                rule.tipoEnfermedad, rule.version, rule.calibrada,
                rule.filas.map { StoredRow(it.orden, it.sevMax, it.incMax, it.radioMinM, it.radioMaxM) },
            )
        }
    }

    @Serializable
    private data class StoredRow(
        val orden: Int,
        val sevMax: Double,
        val incMax: Double,
        val radioMinM: Double,
        val radioMaxM: Double,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val TYPE_PATTERN = Regex("^[a-z0-9_-]{2,50}$")
        val PLANTS_PER_HA = intPreferencesKey("zone_plants_per_ha")
        fun ruleKey(type: String) = stringPreferencesKey("zone_rule_json_$type")
        fun etagKey(type: String) = stringPreferencesKey("zone_rule_etag_$type")
    }
}
