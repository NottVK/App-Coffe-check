package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError

/**
 * Por qué «Enviar ahora» no pudo enviar. Sin esto el botón dejaba de girar sin decir
 * nada y el productor no sabía si había que esperar, conectarse o cambiar algo.
 *
 * Los casos que ya tienen su propio aviso en pantalla (sesión vencida) o que no son
 * un problema (nada pendiente, todo enviado) no generan mensaje.
 */
enum class SendNowFeedback {
    NO_CONNECTION,
    METERED_NETWORK,
    ACCOUNT_NOT_READY,
    RATE_LIMITED,
    SOME_FAILED,
    ERROR,
}

fun AgroResult<SyncSummary, DomainError>.toSendNowFeedback(): SendNowFeedback? = when (this) {
    is AgroResult.Failure ->
        if (error == DomainError.NoConnection) SendNowFeedback.NO_CONNECTION else SendNowFeedback.ERROR

    is AgroResult.Success -> when (value.skipReason) {
        SkipReason.METERED_NETWORK -> SendNowFeedback.METERED_NETWORK
        SkipReason.UPLOAD_GATE -> SendNowFeedback.ACCOUNT_NOT_READY
        SkipReason.RATE_LIMITED -> SendNowFeedback.RATE_LIMITED
        SkipReason.SIGN_IN_REQUIRED, SkipReason.NOTHING_PENDING -> null
        null -> if (value.failed > 0) SendNowFeedback.SOME_FAILED else null
    }
}
