package co.agrovision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Preferencias que afectan al tema, elevadas a la actividad.
 *
 * Vive aparte del resto de ajustes porque el tema tiene que estar resuelto antes
 * de componer nada: leerlo dentro de cada pantalla produciría un parpadeo del
 * tema claro al entrar en modo exterior, que a pleno sol es exactamente el
 * momento en que peor se ve.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = settings.observe().stateIn(
        scope = viewModelScope,
        // `Eagerly` y no `WhileSubscribed`: el valor tiene que estar disponible en
        // la primera composición. Un `WhileSubscribed` daría el valor por defecto
        // durante un frame y luego el real, produciendo el parpadeo.
        started = SharingStarted.Eagerly,
        initialValue = UserPreferences.DEFAULT,
    )
}
