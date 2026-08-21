package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.identity.UserTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estado de la cuenta en el dispositivo.
 *
 * El nivel se guarda localmente pero **la autoridad es el servidor**: T2 la
 * concede un administrador desde la consola, y este valor es una caché de lo que
 * dijo la última sincronización. Nunca se sube de nivel por decisión local.
 *
 * Lo que sí es local y definitivo es el `device_id`, que vive en
 * [DeviceIdentityStore] y sobrevive al registro: es lo que permite la vinculación
 * progresiva, es decir, que las contribuciones que el productor encoló siendo
 * anónimo se le atribuyan retroactivamente al crear su cuenta (§6.3).
 */
@Singleton
class AccountStore @Inject constructor(
    private val store: DataStore<Preferences>,
    private val identity: DeviceIdentityStore,
) {

    fun observe(): Flow<Account> = store.data.map { preferences ->
        toAccount(preferences, preferences[Keys.DEVICE_ID].orEmpty())
    }

    suspend fun current(): Account {
        val deviceId = identity.deviceId()
        return toAccount(store.data.first(), deviceId)
    }

    suspend fun update(transform: (Account) -> Account) {
        val deviceId = identity.deviceId()
        store.edit { preferences ->
            val updated = transform(toAccount(preferences, deviceId))
            preferences[Keys.TIER] = updated.tier.name
            updated.phone?.let { preferences[Keys.PHONE] = it }
            updated.displayName?.let { preferences[Keys.DISPLAY_NAME] = it }
            updated.farmName?.let { preferences[Keys.FARM_NAME] = it }
            updated.vereda?.let { preferences[Keys.VEREDA] = it }
            updated.registeredAtEpochMillis?.let { preferences[Keys.REGISTERED_AT] = it }
            updated.validatedAtEpochMillis?.let { preferences[Keys.VALIDATED_AT] = it }
            preferences[Keys.TRAINING_CONSENT] = updated.trainingConsent
        }
    }

    private fun toAccount(preferences: Preferences, deviceId: String) = Account(
        // Sin clave guardada, anónimo. Es el estado inicial de todo el mundo y el
        // que cubre la mayor parte del valor del producto (§6.1).
        tier = preferences[Keys.TIER]
            ?.let { name -> UserTier.entries.firstOrNull { it.name == name } }
            ?: UserTier.T0_ANONIMO,
        phone = preferences[Keys.PHONE],
        displayName = preferences[Keys.DISPLAY_NAME],
        farmName = preferences[Keys.FARM_NAME],
        vereda = preferences[Keys.VEREDA],
        registeredAtEpochMillis = preferences[Keys.REGISTERED_AT],
        validatedAtEpochMillis = preferences[Keys.VALIDATED_AT],
        deviceId = deviceId,
        trainingConsent = preferences[Keys.TRAINING_CONSENT] ?: false,
    )

    private object Keys {
        val TIER = stringPreferencesKey("account_tier")
        val PHONE = stringPreferencesKey("account_phone")
        val DISPLAY_NAME = stringPreferencesKey("account_display_name")
        val FARM_NAME = stringPreferencesKey("account_farm_name")
        val VEREDA = stringPreferencesKey("account_vereda")
        val REGISTERED_AT = longPreferencesKey("account_registered_at")
        val VALIDATED_AT = longPreferencesKey("account_validated_at")
        val TRAINING_CONSENT = booleanPreferencesKey("training_consent")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
