package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SendNowFeedbackTest {

    private fun summary(uploaded: Int = 0, failed: Int = 0, skip: SkipReason? = null) =
        AgroResult.success(SyncSummary(SyncTrigger.MANUAL, uploaded, failed, skip))

    @Test
    fun `sin conexion avisa que se enviara sola`() {
        val result = AgroResult.failure(DomainError.NoConnection)
        assertEquals(SendNowFeedback.NO_CONNECTION, result.toSendNowFeedback())
    }

    @Test
    fun `datos moviles con politica de solo WiFi lo explica`() {
        assertEquals(SendNowFeedback.METERED_NETWORK, summary(skip = SkipReason.METERED_NETWORK).toSendNowFeedback())
    }

    @Test
    fun `cuenta sin validar lo explica`() {
        assertEquals(SendNowFeedback.ACCOUNT_NOT_READY, summary(skip = SkipReason.UPLOAD_GATE).toSendNowFeedback())
    }

    @Test
    fun `servidor ocupado lo explica`() {
        assertEquals(SendNowFeedback.RATE_LIMITED, summary(skip = SkipReason.RATE_LIMITED).toSendNowFeedback())
    }

    @Test
    fun `algunas fallidas avisa que se reintentara`() {
        assertEquals(SendNowFeedback.SOME_FAILED, summary(uploaded = 1, failed = 2).toSendNowFeedback())
    }

    @Test
    fun `un error inesperado da un mensaje generico`() {
        val result = AgroResult.failure(DomainError.NotSignedIn)
        assertEquals(SendNowFeedback.ERROR, result.toSendNowFeedback())
    }

    @Test
    fun `sesion vencida, nada pendiente o todo enviado no generan mensaje`() {
        assertNull(summary(skip = SkipReason.SIGN_IN_REQUIRED).toSendNowFeedback())
        assertNull(summary(skip = SkipReason.NOTHING_PENDING).toSendNowFeedback())
        assertNull(summary(uploaded = 3).toSendNowFeedback())
    }
}
