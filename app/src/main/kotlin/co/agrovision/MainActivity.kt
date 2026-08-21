package co.agrovision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.agrovision.core.designsystem.theme.AgroAppearance
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.navigation.AgroVisionNavHost
import dagger.hilt.android.AndroidEntryPoint

/**
 * Única actividad de la aplicación.
 *
 * ⚠️ **No la renombres ni la exportes.** El lanzador publicado es
 * `io.ionic.starter.MainActivity`, y esa continuidad la mantiene el
 * `<activity-alias>` del manifiesto. Ver `PRESERVE.md` §8 y el comentario del
 * manifiesto.
 *
 * ## La regla de producto que gobierna esta clase
 *
 * **Aquí no hay inicio de sesión.** El primer contacto es la cámara, siempre, en
 * el primer arranque y en un dispositivo recién instalado. La cuenta se pide solo
 * al intentar enviar una solicitud de revisión, y con contexto.
 *
 * Un muro de registro al instalar elimina la mayoría de instalaciones antes del
 * primer escaneo: es el error prohibido nº 6 (§23). Si algún día aparece una
 * pantalla de acceso antes de la cámara, algo se rompió.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: si no, se ve un parpadeo del tema por defecto
        // en dispositivos lentos, que son la mayoría del parque objetivo.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            val preferences by appearanceViewModel.preferences.collectAsStateWithLifecycle()

            AgroTheme(appearance = preferences.resolveAppearance(isSystemDark())) {
                AgroVisionNavHost()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun isSystemDark(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()
}

/**
 * Resuelve la apariencia efectiva.
 *
 * El modo exterior gana sobre el oscuro cuando está fijado: si el productor lo
 * activó, es porque está a pleno sol, y ahí el tema oscuro es exactamente lo
 * contrario de lo que hace falta.
 *
 * `AUTO` queda pendiente de conectar al sensor de luz ambiental; mientras tanto
 * se comporta como el sistema.
 */
internal fun co.agrovision.core.model.settings.UserPreferences.resolveAppearance(
    systemDark: Boolean,
): AgroAppearance = when (outdoorMode) {
    OutdoorMode.SIEMPRE -> AgroAppearance.OUTDOOR
    OutdoorMode.NUNCA -> if (systemDark) AgroAppearance.DARK else AgroAppearance.LIGHT
    OutdoorMode.AUTO -> if (systemDark) AgroAppearance.DARK else AgroAppearance.LIGHT
}
