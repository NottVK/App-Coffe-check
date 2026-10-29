package co.agrovision.feature.review.create

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import co.agrovision.feature.review.R

/**
 * Diálogos de ubicación de la solicitud. Todos aparecen en contexto, al enviar:
 * la app nunca pide ubicación al arrancar.
 */
@Composable
internal fun LocationPromptDialog(
    prompt: LocationPrompt?,
    onIntent: (CreateReviewIntent) -> Unit,
) {
    if (prompt == null) return
    val context = LocalContext.current
    val content = prompt.content()

    AlertDialog(
        onDismissRequest = { onIntent(CreateReviewIntent.DismissLocationPrompt) },
        title = { Text(stringResource(content.title)) },
        text = { Text(stringResource(content.body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    when (prompt) {
                        LocationPrompt.Consent -> onIntent(CreateReviewIntent.AcceptLocationConsent)
                        LocationPrompt.ConfirmStillAtLot -> onIntent(CreateReviewIntent.ConfirmStillAtLot)
                        LocationPrompt.Timeout -> onIntent(CreateReviewIntent.RetryLocation)
                        // Lleva a la pantalla del sistema y devuelve al formulario:
                        // el productor vuelve a pulsar «Enviar» cuando lo haya activado.
                        LocationPrompt.PermissionDenied -> {
                            context.openAppSettings()
                            onIntent(CreateReviewIntent.DismissLocationPrompt)
                        }
                        LocationPrompt.GpsOff -> {
                            context.openLocationSettings()
                            onIntent(CreateReviewIntent.DismissLocationPrompt)
                        }
                    }
                },
            ) { Text(stringResource(content.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(CreateReviewIntent.DismissLocationPrompt) }) {
                Text(stringResource(R.string.ubic_cancelar))
            }
        },
    )
}

private class PromptContent(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val confirm: Int,
)

private fun LocationPrompt.content() = when (this) {
    LocationPrompt.Consent ->
        PromptContent(R.string.ubic_consent_titulo, R.string.ubic_consent_cuerpo, R.string.ubic_aceptar)
    LocationPrompt.ConfirmStillAtLot ->
        PromptContent(R.string.ubic_lote_titulo, R.string.ubic_lote_cuerpo, R.string.ubic_lote_confirmar)
    LocationPrompt.PermissionDenied ->
        PromptContent(R.string.ubic_permiso_titulo, R.string.ubic_permiso_cuerpo, R.string.ubic_abrir_ajustes)
    LocationPrompt.GpsOff ->
        PromptContent(R.string.ubic_gps_titulo, R.string.ubic_gps_cuerpo, R.string.ubic_gps_activar)
    LocationPrompt.Timeout ->
        PromptContent(R.string.ubic_timeout_titulo, R.string.ubic_timeout_cuerpo, R.string.ubic_reintentar)
}

private fun Context.openAppSettings() = startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

private fun Context.openLocationSettings() = startActivity(
    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)
