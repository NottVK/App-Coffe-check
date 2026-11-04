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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import co.agrovision.R
import co.agrovision.core.designsystem.theme.AgroTheme
import co.agrovision.core.model.mlmodel.ModelUpdateFailure
import co.agrovision.core.model.settings.OutdoorMode
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.feature.review.components.ChoiceGroup

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPolicy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmRevoke by remember { mutableStateOf(false) }
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
            // Solo aparece si el productor ya aceptó en una solicitud; aceptar se
            // hace en contexto, al enviarla, no desde aquí.
            if (preferences.locationShareConsentAtEpochMillis != null) {
                SwitchRow(
                    text = stringResource(R.string.ajustes_ubicacion_solicitudes),
                    supporting = stringResource(R.string.ajustes_ubicacion_solicitudes_ayuda),
                    checked = true,
                    onCheckedChange = { keep -> if (!keep) viewModel.revokeLocationShare() },
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

        Section(title = stringResource(R.string.ajustes_modelo), gutter = gutter) {
            ModelSection(state = state.model, onCheck = viewModel::checkModelNow)
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

            // Habeas data: leer la política y retirar la autorización. Es aparte del
            // interruptor de entrenamiento: son consentimientos distintos.
            Text(
                text = stringResource(R.string.ajustes_politica),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.dataPolicyAcceptedAtEpochMillis
                    ?.let { stringResource(R.string.ajustes_politica_aceptada, state.dataPolicyVersion.orEmpty(), formatDay(it)) }
                    ?: stringResource(R.string.ajustes_politica_no_aceptada),
                style = MaterialTheme.typography.bodyMedium,
                color = AgroTheme.semantic.mutedText,
            )
            if (state.policyNotSynced) {
                Text(
                    text = stringResource(R.string.ajustes_politica_sin_enviar),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onOpenPolicy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ajustes_politica_leer))
            }
            if (state.dataPolicyVersion != null) {
                TextButton(onClick = { confirmRevoke = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.ajustes_politica_revocar),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.ajustes_version, versionName(context)),
            style = MaterialTheme.typography.labelLarge,
            color = AgroTheme.semantic.mutedText,
            modifier = Modifier.padding(horizontal = gutter),
        )
    }

    if (confirmRevoke) {
        RevokePolicyDialog(
            onConfirm = {
                confirmRevoke = false
                viewModel.revokeDataPolicy()
            },
            onDismiss = { confirmRevoke = false },
        )
    }
}

@Composable
private fun RevokePolicyDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ajustes_politica_revocar_titulo)) },
        text = { Text(stringResource(R.string.ajustes_politica_revocar_cuerpo)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.ajustes_politica_revocar_confirmar),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ajustes_politica_cancelar)) }
        },
    )
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

private fun OutdoorMode.labelRes(): Int = when (this) {
    OutdoorMode.AUTO -> R.string.ajustes_exterior_auto
    OutdoorMode.SIEMPRE -> R.string.ajustes_exterior_siempre
    OutdoorMode.NUNCA -> R.string.ajustes_exterior_nunca
}

private fun versionName(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

@Composable
private fun ModelSection(state: ModelStatusUi, onCheck: () -> Unit) {
    val muted = AgroTheme.semantic.mutedText
    val checking = state.phase == ModelCheckPhase.CHECKING

    Text(
        text = state.activeVersion?.let { stringResource(R.string.ajustes_modelo_version, it) }
            ?: stringResource(R.string.ajustes_modelo_fabrica),
        style = MaterialTheme.typography.titleMedium,
    )
    if (state.installedAtEpochMillis > 0L) {
        Text(
            text = stringResource(R.string.ajustes_modelo_instalado, formatDate(state.installedAtEpochMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
    }
    Text(
        text = if (state.lastCheckEpochMillis > 0L) {
            stringResource(R.string.ajustes_modelo_revisado, formatDate(state.lastCheckEpochMillis))
        } else {
            stringResource(R.string.ajustes_modelo_nunca_revisado)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = muted,
    )

    val (message, isError) = when (state.phase) {
        ModelCheckPhase.IDLE -> null to false
        ModelCheckPhase.CHECKING -> stringResource(R.string.ajustes_modelo_buscando) to false
        ModelCheckPhase.UP_TO_DATE -> stringResource(R.string.ajustes_modelo_al_dia) to false
        ModelCheckPhase.UPDATED ->
            stringResource(R.string.ajustes_modelo_actualizado, state.updatedToVersion.orEmpty()) to false
        ModelCheckPhase.REQUIRES_APP_UPDATE ->
            stringResource(R.string.ajustes_modelo_requiere_app, state.requiredAppVersion.orEmpty()) to true
        ModelCheckPhase.FAILED -> stringResource(R.string.ajustes_modelo_fallo) to true
    }
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
    // Un fallo anterior (p. ej. firma inválida) sigue visible hasta que una
    // actualización lo supere: antes solo quedaba en el log.
    if (state.lastFailure != null && state.phase == ModelCheckPhase.IDLE) {
        Text(
            text = stringResource(state.lastFailure.messageRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Button(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.ajustes_modelo_buscar))
    }
}

private fun ModelUpdateFailure.messageRes(): Int = when (this) {
    ModelUpdateFailure.NETWORK -> R.string.ajustes_modelo_err_red
    ModelUpdateFailure.INTEGRITY_MISMATCH -> R.string.ajustes_modelo_err_integridad
    ModelUpdateFailure.SIGNATURE_INVALID -> R.string.ajustes_modelo_err_firma
    ModelUpdateFailure.CALIBRATION_MISSING -> R.string.ajustes_modelo_err_calibracion
    ModelUpdateFailure.SMOKE_TEST_FAILED -> R.string.ajustes_modelo_err_prueba
    ModelUpdateFailure.STORAGE_FULL -> R.string.ajustes_modelo_err_espacio
    ModelUpdateFailure.INCOMPATIBLE_APP_VERSION -> R.string.ajustes_modelo_err_app
}

private fun formatDay(epochMillis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMillis))

private fun formatDate(epochMillis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(java.util.Date(epochMillis))
