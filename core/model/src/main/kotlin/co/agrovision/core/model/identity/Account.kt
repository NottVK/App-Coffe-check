package co.agrovision.core.model.identity

/**
 * Los cuatro niveles del modelo de identidad (§6.1). En el dispositivo solo
 * existen los tres primeros: T3 y T4 operan en la consola web.
 */
enum class UserTier {
    /** Sin registro. Escanea sin límite, catálogo completo, historial local,
     *  correcciones locales y OTA de modelo. Es el estado inicial y el que
     *  cubre la mayoría del valor del producto. */
    T0_ANONIMO,

    /** Teléfono verificado por OTP. Puede redactar y encolar solicitudes; sus
     *  solicitudes aún no llegan a la bandeja del agrónomo. */
    T1_REGISTRADO,

    /** Aprobado por un administrador. Sus solicitudes entran a la bandeja y sus
     *  imágenes alimentan el dataset. */
    T2_VALIDADO;

    val canUpload: Boolean get() = this == T2_VALIDADO
    val canCreateRequests: Boolean get() = this != T0_ANONIMO
}

data class Account(
    val tier: UserTier,
    val phone: String?,
    val displayName: String?,
    val farmName: String?,
    val vereda: String?,
    val registeredAtEpochMillis: Long?,
    val validatedAtEpochMillis: Long?,
    /**
     * Identificador anónimo generado en el dispositivo. Sin relación con
     * identificadores de hardware ni publicitarios (§12.2). Sirve para tres
     * cosas: limitación de tasa en la superficie pública, asignación
     * determinística al canal canario y vinculación progresiva de la cuenta.
     */
    val deviceId: String,
    /** Consentimiento explícito y revocable para uso de imágenes en
     *  entrenamiento. La revocación excluye las muestras de futuras
     *  instantáneas de dataset. */
    val trainingConsent: Boolean,
) {
    companion object {
        fun anonymous(deviceId: String) = Account(
            tier = UserTier.T0_ANONIMO,
            phone = null,
            displayName = null,
            farmName = null,
            vereda = null,
            registeredAtEpochMillis = null,
            validatedAtEpochMillis = null,
            deviceId = deviceId,
            trainingConsent = false,
        )
    }
}

/**
 * Compuerta de subida, evaluada AL SINCRONIZAR y nunca al crear (§6.3).
 *
 * La app encola contribuciones sin importar el nivel del usuario. Un productor
 * anónimo que documenta una plaga rara no pierde ese trabajo: queda en cola y
 * sube cuando su cuenta se valide.
 *
 * El error opuesto —bloquear la creación hasta tener cuenta— es el error
 * prohibido nº 6 (§23): un muro de registro elimina la mayoría de instalaciones
 * antes del primer escaneo.
 */
sealed interface UploadGate {

    data object Allowed : UploadGate

    data class BlockedNeedsAccount(val queuedCount: Int) : UploadGate

    data class BlockedPendingValidation(
        val queuedCount: Int,
        val sinceEpochMillis: Long,
    ) : UploadGate

    val isBlocked: Boolean get() = this !is Allowed

    companion object {
        fun evaluate(tier: UserTier, queuedCount: Int, registeredAtEpochMillis: Long?): UploadGate =
            when (tier) {
                UserTier.T0_ANONIMO -> BlockedNeedsAccount(queuedCount)
                UserTier.T1_REGISTRADO -> BlockedPendingValidation(
                    queuedCount = queuedCount,
                    sinceEpochMillis = registeredAtEpochMillis ?: 0L,
                )
                UserTier.T2_VALIDADO -> Allowed
            }
    }
}
