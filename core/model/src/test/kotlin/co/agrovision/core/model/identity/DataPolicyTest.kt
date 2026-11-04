package co.agrovision.core.model.identity

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Habeas data: la política se pide una vez, y de nuevo si se revoca o cambia. */
class DataPolicyTest {

    private val account = Account.anonymous("device")

    @Test
    fun `quien nunca la aceptó la necesita`() {
        assertTrue(account.needsPolicy)
    }

    @Test
    fun `quien aceptó la versión vigente no la necesita de nuevo`() {
        val accepted = account.copy(
            dataPolicyVersion = DataPolicy.CURRENT_VERSION,
            dataPolicyAcceptedAtEpochMillis = 1L,
        )

        assertFalse(accepted.needsPolicy)
    }

    @Test
    fun `una versión anterior ya no cuenta`() {
        val old = account.copy(dataPolicyVersion = "0.9", dataPolicyAcceptedAtEpochMillis = 1L)

        assertTrue(old.needsPolicy)
    }

    @Test
    fun `al revocar vuelve a pedirse`() {
        val revoked = account
            .copy(dataPolicyVersion = DataPolicy.CURRENT_VERSION, dataPolicyAcceptedAtEpochMillis = 1L)
            .copy(dataPolicyVersion = null, dataPolicyAcceptedAtEpochMillis = null)

        assertTrue(revoked.needsPolicy)
    }
}
