package co.agrovision.core.model.usecase

import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.SettingsRepository
import javax.inject.Inject

/**
 * Consentimiento para compartir la ubicación EXACTA de la foto al pedir una
 * revisión. Se pide en contexto (al enviar la solicitud), nunca al arrancar.
 *
 * Se guarda con la fecha de aceptación en las preferencias del dispositivo, junto
 * a los demás consentimientos del productor; no es el de entrenamiento
 * (`trainingConsent`, uso de imágenes para el dataset) ni el de ubicación de las
 * capturas (`locationEnabled`): cubre otro propósito y otros destinatarios
 * —el administrador y el agrónomo asignado—.
 */
class LocationShareConsentUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend fun isGranted(): Boolean =
        settings.current().locationShareConsentAtEpochMillis != null

    suspend fun grant() {
        val now = clock.nowEpochMillis()
        settings.update { it.copy(locationShareConsentAtEpochMillis = now) }
    }

    /** El productor retira el permiso: la siguiente solicitud vuelve a preguntar. */
    suspend fun revoke() {
        settings.update { it.copy(locationShareConsentAtEpochMillis = null) }
    }
}
