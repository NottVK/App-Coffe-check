package co.agrovision.core.model.settings

import co.agrovision.core.model.capture.LocationPrecision

data class UserPreferences(
    val syncNetworkPolicy: SyncNetworkPolicy,
    val locationEnabled: Boolean,
    val locationPrecision: LocationPrecision,
    val outdoorMode: OutdoorMode,
    val voiceReadout: Boolean,
    val trainingConsent: Boolean,
    val notificationsEnabled: Boolean,
) {
    companion object {
        /**
         * Valores iniciales. Cada uno es una decisión, no un descuido:
         *  · `WIFI_Y_DATOS` porque en vereda la única conectividad suele ser
         *    datos móviles, y `SOLO_WIFI` condenaría las capturas a no
         *    sincronizarse nunca (§9.4).
         *  · Ubicación desactivada: se pide en contexto, no al arrancar (§12.2).
         *  · Sin consentimiento de entrenamiento: se pide explícitamente.
         */
        val DEFAULT = UserPreferences(
            syncNetworkPolicy = SyncNetworkPolicy.WIFI_Y_DATOS,
            locationEnabled = false,
            locationPrecision = LocationPrecision.VEREDA,
            outdoorMode = OutdoorMode.AUTO,
            voiceReadout = false,
            trainingConsent = false,
            notificationsEnabled = true,
        )
    }
}

/**
 * ADR-008: no se exige red no medida.
 *
 * Forzar WiFi es lo que hace por defecto casi cualquier app, y aquí sería un
 * error: en zona rural la única conectividad disponible suele ser de datos
 * móviles. La decisión sobre el gasto de datos le corresponde a quien lo paga,
 * así que se expone como ajuste explícito con el volumen pendiente visible en
 * megabytes.
 */
enum class SyncNetworkPolicy {
    SOLO_WIFI,
    WIFI_Y_DATOS,
    PREGUNTAR;

    val allowsMetered: Boolean get() = this == WIFI_Y_DATOS
}

/**
 * Modo exterior: variante de tema con contraste reforzado para uso a pleno sol
 * (§12.7). El contexto de uso es de pie en el lote al mediodía, no una oficina.
 */
enum class OutdoorMode {
    /** Se activa según el sensor de luz ambiental. */
    AUTO,
    SIEMPRE,
    NUNCA,
}
