package co.agrovision.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.mlmodel.ModelRegistryState
import co.agrovision.core.model.mlmodel.ModelUpdateDecision
import co.agrovision.core.model.mlmodel.ModelUpdateFailure
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.core.model.settings.UserPreferences
import co.agrovision.core.model.usecase.ActivateModelUseCase
import co.agrovision.core.model.usecase.CheckModelUpdateUseCase
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
    /** Habeas data: política de datos que el productor aceptó y cuándo (null si no
     *  la ha aceptado o la revocó). */
    val dataPolicyVersion: String? = null,
    val dataPolicyAcceptedAtEpochMillis: Long? = null,
    /** La revocación no llegó al servidor: la interfaz no puede darla por hecha. */
    val policyNotSynced: Boolean = false,
    val model: ModelStatusUi = ModelStatusUi(),
)

/** Qué está haciendo la búsqueda manual de actualizaciones del modelo. */
enum class ModelCheckPhase { IDLE, CHECKING, UP_TO_DATE, UPDATED, REQUIRES_APP_UPDATE, FAILED }

@Immutable
data class ModelStatusUi(
    /** Versión del modelo activo; nula si la app aún usa el de fábrica. */
    val activeVersion: String? = null,
    val installedAtEpochMillis: Long = 0L,
    val lastCheckEpochMillis: Long = 0L,
    val lastFailure: ModelUpdateFailure? = null,
    val phase: ModelCheckPhase = ModelCheckPhase.IDLE,
    val updatedToVersion: String? = null,
    val requiredAppVersion: String? = null,
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
    private val registry: ModelRegistryRepository,
    private val checkModelUpdate: CheckModelUpdateUseCase,
    private val activateModel: ActivateModelUseCase,
) : ViewModel() {

    private val consentNotSynced = MutableStateFlow(false)
    private val policyNotSynced = MutableStateFlow(false)
    private val modelCheck = MutableStateFlow(ModelStatusUi())

    val state: StateFlow<SettingsUiState> = combine(
        settings.observe(),
        account.observe(),
        combine(consentNotSynced, policyNotSynced) { consent, policy -> consent to policy },
        registry.observeState(),
        modelCheck,
    ) { preferences, current, unsynced, registryState, check ->
        SettingsUiState(
            preferences = preferences,
            trainingConsent = current.trainingConsent,
            consentNotSynced = unsynced.first,
            dataPolicyVersion = current.dataPolicyVersion,
            dataPolicyAcceptedAtEpochMillis = current.dataPolicyAcceptedAtEpochMillis,
            policyNotSynced = unsynced.second,
            model = check.copy(
                activeVersion = registryState.active?.version,
                installedAtEpochMillis = registryState.active?.installedAtEpochMillis ?: 0L,
                lastCheckEpochMillis = registryState.lastCheckEpochMillis,
                lastFailure = registryState.lastFailure,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    fun setSyncPolicy(policy: SyncNetworkPolicy) = update { it.copy(syncNetworkPolicy = policy) }

    fun setLocationEnabled(enabled: Boolean) = update { it.copy(locationEnabled = enabled) }

    /** Retira el permiso de compartir la ubicación exacta en solicitudes; la
     *  próxima solicitud vuelve a preguntar. */
    fun revokeLocationShare() = update { it.copy(locationShareConsentAtEpochMillis = null) }

    fun setOutdoorMode(mode: OutdoorMode) = update { it.copy(outdoorMode = mode) }

    fun setVoiceReadout(enabled: Boolean) = update { it.copy(voiceReadout = enabled) }

    fun setNotifications(enabled: Boolean) = update { it.copy(notificationsEnabled = enabled) }

    fun setTrainingConsent(granted: Boolean) {
        viewModelScope.launch {
            val result = account.setTrainingConsent(granted)
            consentNotSynced.update { result is AgroResult.Failure }
        }
    }

    /** Retira la autorización de tratamiento de datos (habeas data). La próxima
     *  solicitud vuelve a pedirla. */
    fun revokeDataPolicy() {
        viewModelScope.launch {
            val result = account.revokeDataPolicy()
            policyNotSynced.update { result is AgroResult.Failure }
        }
    }

    /** Búsqueda manual: salta el límite de 24 h y, si hay versión nueva, la instala. */
    fun checkModelNow() {
        if (modelCheck.value.phase == ModelCheckPhase.CHECKING) return
        modelCheck.value = ModelStatusUi(phase = ModelCheckPhase.CHECKING)
        viewModelScope.launch {
            val next = when (val result = checkModelUpdate(force = true)) {
                is AgroResult.Failure -> ModelStatusUi(phase = ModelCheckPhase.FAILED)
                is AgroResult.Success -> when (val decision = result.value) {
                    ModelUpdateDecision.UpToDate -> ModelStatusUi(phase = ModelCheckPhase.UP_TO_DATE)
                    is ModelUpdateDecision.RequiresAppUpdate -> ModelStatusUi(
                        phase = ModelCheckPhase.REQUIRES_APP_UPDATE,
                        requiredAppVersion = decision.minAppVersion,
                    )
                    is ModelUpdateDecision.KillSwitchTriggered -> ModelStatusUi(phase = ModelCheckPhase.UP_TO_DATE)
                    is ModelUpdateDecision.Available -> when (activateModel(decision.manifest)) {
                        is AgroResult.Success -> ModelStatusUi(
                            phase = ModelCheckPhase.UPDATED,
                            updatedToVersion = decision.manifest.version,
                        )
                        is AgroResult.Failure -> ModelStatusUi(phase = ModelCheckPhase.FAILED)
                    }
                }
            }
            modelCheck.value = next
        }
    }

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { settings.update(transform) }
    }
}
