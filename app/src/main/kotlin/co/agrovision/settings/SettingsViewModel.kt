package co.agrovision.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.capture.LocationPrecision
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.core.model.settings.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences.DEFAULT,
    /** El consentimiento vive en la cuenta, no en las preferencias: es lo que el
     *  servidor conoce y lo que se revoca allí. */
    val trainingConsent: Boolean = false,
    /** La última vez que se cambió el consentimiento no llegó al servidor. */
    val consentNotSynced: Boolean = false,
)

/**
 * Pantalla de ajustes. Cada control escribe en el almacén local; el
 * consentimiento de entrenamiento además avisa al servidor, porque revocarlo
 * tiene que excluir las fotos de futuras instantáneas del dataset.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val account: AccountRepository,
) : ViewModel() {

    private val consentNotSynced = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        settings.observe(),
        account.observe(),
        consentNotSynced,
    ) { preferences, current, notSynced ->
        SettingsUiState(
            preferences = preferences,
            trainingConsent = current.trainingConsent,
            consentNotSynced = notSynced,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    fun setSyncPolicy(policy: SyncNetworkPolicy) = update { it.copy(syncNetworkPolicy = policy) }

    fun setLocationEnabled(enabled: Boolean) = update { it.copy(locationEnabled = enabled) }

    fun setLocationPrecision(precision: LocationPrecision) =
        update { it.copy(locationPrecision = precision) }

    fun setOutdoorMode(mode: OutdoorMode) = update { it.copy(outdoorMode = mode) }

    fun setVoiceReadout(enabled: Boolean) = update { it.copy(voiceReadout = enabled) }

    fun setNotifications(enabled: Boolean) = update { it.copy(notificationsEnabled = enabled) }

    fun setTrainingConsent(granted: Boolean) {
        viewModelScope.launch {
            val result = account.setTrainingConsent(granted)
            consentNotSynced.update { result is AgroResult.Failure }
        }
    }

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { settings.update(transform) }
    }
}
