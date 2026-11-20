package co.agrovision.core.data.repository

import co.agrovision.core.common.AgroResult
import co.agrovision.core.database.dao.CaptureDao
import co.agrovision.core.database.dao.ReviewDao
import co.agrovision.core.database.dao.SyncCursorDao
import co.agrovision.core.datastore.AccountStore
import co.agrovision.core.datastore.SessionTokenStore
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.identity.Account
import co.agrovision.core.model.identity.DataPolicy
import co.agrovision.core.model.identity.ProducerProfile
import co.agrovision.core.model.identity.UserTier
import co.agrovision.core.model.port.AccountGateway
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ProfileRegistration
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Entrar con otro teléfono no puede heredar la cuenta anterior: ni su política
 * aceptada, ni sus consentimientos, ni la cola que todavía no salió.
 */
class AccountSwitchTest {

    private var account = Account.anonymous("device").copy(
        tier = UserTier.T2_VALIDADO,
        phone = "+573001111111",
        displayName = "Ana",
        farmName = "La Primera",
        trainingConsent = true,
        dataPolicyVersion = DataPolicy.CURRENT_VERSION,
        registeredAtEpochMillis = 1L,
    )
    private var preferences = UserPreferences.DEFAULT.copy(trainingConsent = true, locationShareConsentAtEpochMillis = 5L)

    private val store = mockk<AccountStore>().also {
        coEvery { it.current() } answers { account }
        coEvery { it.update(any()) } answers { account = firstArg<(Account) -> Account>()(account) }
    }
    private val settings = mockk<SettingsRepository>().also {
        coEvery { it.current() } answers { preferences }
        coEvery { it.update(any()) } answers {
            preferences = firstArg<(UserPreferences) -> UserPreferences>()(preferences)
        }
    }
    private val tokens = mockk<SessionTokenStore>(relaxed = true)
    private val captureDao = mockk<CaptureDao>(relaxed = true)
    private val reviewDao = mockk<ReviewDao>(relaxed = true)
    private val cursors = mockk<SyncCursorDao>(relaxed = true)

    private val repository = AccountRepositoryImpl(
        store = store,
        tokens = tokens,
        gateway = FakeGateway(),
        clock = Clock { 100L },
        settings = settings,
        captureDao = captureDao,
        reviewDao = reviewDao,
        cursors = cursors,
    )

    @Test
    fun `otro telefono no hereda la cuenta ni la cola anterior`() = runTest {
        repository.verifyOtp("3002222222", "123456")

        assertEquals("3002222222", account.phone)
        assertEquals(UserTier.T1_REGISTRADO, account.tier)
        assertNull(account.dataPolicyVersion)
        assertNull(account.farmName)
        assertFalse(account.trainingConsent)
        assertNull(preferences.locationShareConsentAtEpochMillis)
        coVerify { captureDao.skipUnsent() }
        coVerify { reviewDao.abandonUnsent(any()) }
        coVerify { cursors.delete(any()) }
    }

    @Test
    fun `el mismo telefono con otro formato conserva la cuenta`() = runTest {
        repository.verifyOtp("300 111 1111", "123456")

        assertEquals(DataPolicy.CURRENT_VERSION, account.dataPolicyVersion)
        assertEquals("La Primera", account.farmName)
        coVerify(exactly = 0) { reviewDao.abandonUnsent(any()) }
    }

    /** A mano: mockk no desempaqueta `AgroResult` como retorno de una función suspendida. */
    private class FakeGateway : AccountGateway {
        override suspend fun requestOtp(phone: String) = AgroResult.unit
        override suspend fun verifyOtp(phone: String, code: String): AgroResult<String, DomainError> =
            AgroResult.success("jwt")
        override suspend fun fetchProducerProfile(): AgroResult<ProducerProfile?, DomainError> =
            AgroResult.failure(DomainError.NoConnection)
        override suspend fun saveProducerProfile(profile: ProducerProfile): AgroResult<ProfileRegistration, DomainError> =
            AgroResult.failure(DomainError.NoConnection)
        override suspend fun setTrainingConsent(granted: Boolean) = AgroResult.unit
        override suspend fun acceptDataPolicy(version: String) = AgroResult.unit
        override suspend fun revokeDataPolicy() = AgroResult.unit
    }
}
