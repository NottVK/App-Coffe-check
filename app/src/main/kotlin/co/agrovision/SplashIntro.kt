package co.agrovision

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.agrovision.core.designsystem.theme.AgroTheme
import kotlinx.coroutines.delay

/**
 * Pantalla de bienvenida breve, justo después de la splash del sistema.
 *
 * La splash nativa de Android no admite texto, por eso el mensaje «Funciona sin
 * señal» vive aquí. Mismo fondo que la splash para que el cambio sea invisible.
 * Se muestra una sola vez por apertura (sobrevive a rotaciones).
 */
@Composable
fun SplashIntro(content: @Composable () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(INTRO_MS)
        visible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(visible = visible, exit = fadeOut()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorResource(R.color.mist)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_coffecheck),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(24.dp)),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .clip(CircleShape)
                        .background(AgroTheme.semantic.card)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.home_sin_senal),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
    }
}

private const val INTRO_MS = 3_400L
