package co.agrovision.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import co.agrovision.R
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.capture.LocationPrecision
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.feature.review.components.ChoiceGroup

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gutter = AgroTheme.dimensions.gutter
    val context = LocalContext.current
    val preferences = state.preferences

    // La ubicación se enciende solo si el sistema concede el permiso: un
    // interruptor activo sin permiso diría algo que no es verdad.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> viewModel.setLocationEnabled(granted.values.any { it }) }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.ajustes_volver),
                )
            }
            Text(
                text = stringResource(R.string.ajustes_titulo),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        Section(title = stringResource(R.string.ajustes_datos), gutter = gutter) {
            Text(
                text = stringResource(R.string.ajustes_datos_ayuda),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
            ChoiceGroup(
                options = SyncNetworkPolicy.entries,
                selected = preferences.syncNetworkPolicy,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::setSyncPolicy,
            )
        }

        Section(title = stringResource(R.string.ajustes_ubicacion), gutter = gutter) {
            SwitchRow(
                text = stringResource(R.string.ajustes_ubicacion_usar),
                supporting = stringResource(R.string.ajustes_ubicacion_ayuda),
                checked = preferences.locationEnabled,
                onCheckedChange = { enabled ->
                    when {
                        !enabled -> viewModel.setLocationEnabled(false)
                        hasLocationPermission() -> viewModel.setLocationEnabled(true)
                        else -> locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                        )
                    }
                },
            )
            if (preferences.locationEnabled) {
                ChoiceGroup(
                    options = LocationPrecision.entries,
                    selected = preferences.locationPrecision,
                    label = { stringResource(it.labelRes()) },
                    onSelect = viewModel::setLocationPrecision,
                )
            }
        }

        Section(title = stringResource(R.string.ajustes_pantalla), gutter = gutter) {
            Text(
                text = stringResource(R.string.ajustes_exterior),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.ajustes_exterior_ayuda),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
            ChoiceGroup(
                options = OutdoorMode.entries,
                selected = preferences.outdoorMode,
                label = { stringResource(it.labelRes()) },
                onSelect = viewModel::setOutdoorMode,
            )
            SwitchRow(
                text = stringResource(R.string.ajustes_voz),
                supporting = stringResource(R.string.ajustes_voz_ayuda),
                checked = preferences.voiceReadout,
                onCheckedChange = viewModel::setVoiceReadout,
            )
        }

        Section(title = stringResource(R.string.ajustes_avisos), gutter = gutter) {
            SwitchRow(
                text = stringResource(R.string.ajustes_notificaciones),
                supporting = stringResource(R.string.ajustes_notificaciones_ayuda),
                checked = preferences.notificationsEnabled,
                onCheckedChange = viewModel::setNotifications,
            )
        }

        Section(title = stringResource(R.string.ajustes_privacidad), gutter = gutter) {
            SwitchRow(
                text = stringResource(R.string.ajustes_entrenamiento),
                supporting = stringResource(R.string.ajustes_entrenamiento_ayuda),
                checked = state.trainingConsent,
                onCheckedChange = viewModel::setTrainingConsent,
            )
            if (state.consentNotSynced) {
                Text(
                    text = stringResource(R.string.ajustes_entrenamiento_sin_enviar),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = stringResource(R.string.ajustes_version, versionName(context)),
            style = MaterialTheme.typography.labelLarge,
            color = AgroTheme.semantic.mutedText,
            modifier = Modifier.padding(horizontal = gutter),
        )
    }
}

@Composable
private fun Section(
    title: String,
    gutter: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
private fun SwitchRow(
    text: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = text, style = MaterialTheme.typography.titleMedium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun SyncNetworkPolicy.labelRes(): Int = when (this) {
    SyncNetworkPolicy.SOLO_WIFI -> R.string.ajustes_red_wifi
    SyncNetworkPolicy.WIFI_Y_DATOS -> R.string.ajustes_red_datos
    SyncNetworkPolicy.PREGUNTAR -> R.string.ajustes_red_preguntar
}

private fun LocationPrecision.labelRes(): Int = when (this) {
    LocationPrecision.EXACT -> R.string.ajustes_ubicacion_exacta
    LocationPrecision.VEREDA -> R.string.ajustes_ubicacion_vereda
}

private fun OutdoorMode.labelRes(): Int = when (this) {
    OutdoorMode.AUTO -> R.string.ajustes_exterior_auto
    OutdoorMode.SIEMPRE -> R.string.ajustes_exterior_siempre
    OutdoorMode.NUNCA -> R.string.ajustes_exterior_nunca
}

private fun versionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
