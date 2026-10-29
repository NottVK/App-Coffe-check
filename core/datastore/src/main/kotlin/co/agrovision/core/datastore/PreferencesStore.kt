package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.agrovision.core.common.UuidV7
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.core.model.settings.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferencias del productor.
 *
 * Aquí sí se usa **Preferences DataStore** y no un serializador tipado (ADR-015):
 * los campos son independientes y no hay ninguna invariante entre ellos. Al
 * contrario que el puntero de ranura de modelo, donde escribir dos campos por
 * separado puede dejar el sistema en un estado imposible.
 *
 * ⚠️ **Las claves de preferencias son parte del contrato de identidad** (§2.1).
 * Renombrar una no produce ningún error: simplemente, tras actualizar, el
 * productor descubre que su ajuste de consumo de datos volvió a un valor que él
 * no eligió. Si alguna vez hay que cambiar una clave, se migra explícitamente al
 * arrancar leyendo la vieja y escribiendo la nueva.
 */
@Singleton
class PreferencesStore @Inject constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override fun observe(): Flow<UserPreferences> = store.data.map(::toDomain)

    override suspend fun current(): UserPreferences = toDomain(store.data.first())

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        store.edit { preferences ->
            val updated = transform(toDomain(preferences))
            preferences[Keys.SYNC_POLICY] = updated.syncNetworkPolicy.name
            preferences[Keys.LOCATION_ENABLED] = updated.locationEnabled
            preferences[Keys.OUTDOOR_MODE] = updated.outdoorMode.name
            preferences[Keys.VOICE_READOUT] = updated.voiceReadout
            preferences[Keys.TRAINING_CONSENT] = updated.trainingConsent
            preferences[Keys.NOTIFICATIONS] = updated.notificationsEnabled
            val shareConsent = updated.locationShareConsentAtEpochMillis
            if (shareConsent != null) {
                preferences[Keys.LOCATION_SHARE_CONSENT_AT] = shareConsent
            } else {
                preferences.remove(Keys.LOCATION_SHARE_CONSENT_AT)
            }
        }
    }

    /**
     * Toda lectura cae al valor por defecto documentado si la clave no existe.
     * Nunca se lanza por una preferencia ausente: es el estado normal en el
     * primer arranque y tras una instalación limpia.
     */
    private fun toDomain(preferences: Preferences) = UserPreferences(
        syncNetworkPolicy = preferences[Keys.SYNC_POLICY]
            ?.let { name -> SyncNetworkPolicy.entries.firstOrNull { it.name == name } }
            ?: UserPreferences.DEFAULT.syncNetworkPolicy,
        locationEnabled = preferences[Keys.LOCATION_ENABLED]
            ?: UserPreferences.DEFAULT.locationEnabled,
        outdoorMode = preferences[Keys.OUTDOOR_MODE]
            ?.let { name -> OutdoorMode.entries.firstOrNull { it.name == name } }
            ?: UserPreferences.DEFAULT.outdoorMode,
        voiceReadout = preferences[Keys.VOICE_READOUT] ?: UserPreferences.DEFAULT.voiceReadout,
        trainingConsent = preferences[Keys.TRAINING_CONSENT]
            ?: UserPreferences.DEFAULT.trainingConsent,
        notificationsEnabled = preferences[Keys.NOTIFICATIONS]
            ?: UserPreferences.DEFAULT.notificationsEnabled,
        locationShareConsentAtEpochMillis = preferences[Keys.LOCATION_SHARE_CONSENT_AT],
    )

    private object Keys {
        val SYNC_POLICY = stringPreferencesKey("sync_network_policy")
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val OUTDOOR_MODE = stringPreferencesKey("outdoor_mode")
        val VOICE_READOUT = booleanPreferencesKey("voice_readout")
        val TRAINING_CONSENT = booleanPreferencesKey("training_consent")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val LOCATION_SHARE_CONSENT_AT = longPreferencesKey("location_share_consent_at")
    }
}

/**
 * Identidad anónima del dispositivo.
 *
 * Un UUIDv7 generado localmente, **sin relación con identificadores de hardware
 * ni publicitarios** (§12.2). Se usa para limitar la tasa en la superficie
 * pública, para asignar el canal canario de forma determinística y para la
 * vinculación progresiva de cuenta.
 *
 * Se genera una sola vez y sobrevive a todo salvo a desinstalar. Que sea estable
 * es lo que permite atribuir un cambio en las métricas de campo a la versión del
 * modelo y no al ruido de muestreo: un dispositivo asignado al canario tiene que
 * permanecer en él entre consultas (§8.4).
 */
@Singleton
class DeviceIdentityStore @Inject constructor(
    private val store: DataStore<Preferences>,
) {

    suspend fun deviceId(): String {
        store.data.first()[KEY]?.let { return it }

        // `edit` es transaccional: dos corrutinas pidiendo el identificador a la
        // vez en el primer arranque no generan dos UUID distintos.
        var resolved = ""
        store.edit { preferences ->
            resolved = preferences[KEY] ?: UuidV7.generateString(System.currentTimeMillis())
            preferences[KEY] = resolved
        }
        return resolved
    }

    fun observe(): Flow<String?> = store.data.map { it[KEY] }

    /**
     * Bucket determinístico para el canal canario (§8.4).
     *
     * Se usa el resto de un hash sobre el identificador, **nunca un sorteo
     * aleatorio**: un dispositivo asignado al canario debe permanecer en él entre
     * consultas. Con sorteo, un mismo teléfono entraría y saldría del canario y
     * las métricas de campo dejarían de poder atribuirse a la versión.
     */
    fun canaryBucket(deviceId: String): Int {
        // Los paréntesis importan: `%` liga más fuerte que `and`, y sin ellos el
        // resultado no está acotado a [0, BUCKETS) — la asignación al canario
        // saldría sesgada sin que nada fallara de forma visible.
        val unsigned = deviceId.hashCode().toLong() and UNSIGNED_MASK
        return (unsigned % BUCKETS).toInt()
    }

    /** Contador de arranques. Alimenta el diagnóstico y la decisión de cuándo
     *  pedir consentimiento sin resultar insistente. */
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        store.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    /** Marca de que la importación desde Capacitor ya corrió (ADR-013). */
    suspend fun isLegacyImportDone(): Boolean = store.data.first()[LEGACY_IMPORT] ?: false

    suspend fun markLegacyImportDone(imported: Int) {
        store.edit { preferences ->
            preferences[LEGACY_IMPORT] = true
            preferences[LEGACY_IMPORT_COUNT] = imported
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("device_id")
        val LAUNCH_COUNT = intPreferencesKey("launch_count")
        val LEGACY_IMPORT = booleanPreferencesKey("legacy_import_done")
        val LEGACY_IMPORT_COUNT = intPreferencesKey("legacy_import_count")
        const val BUCKETS = 100L
        const val UNSIGNED_MASK = 0xFFFFFFFFL
    }
}
