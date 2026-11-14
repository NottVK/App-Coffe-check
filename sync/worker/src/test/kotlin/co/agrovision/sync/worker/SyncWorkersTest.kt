package co.agrovision.sync.worker

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import co.agrovision.core.common.AgroConstants
import co.agrovision.core.data.catalog.CatalogSeeder
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.port.ConnectivityStatus
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.settings.SyncNetworkPolicy
import co.agrovision.core.model.usecase.PurgeExpiredCapturesUseCase
import co.agrovision.core.model.usecase.SyncPendingUseCase
import co.agrovision.network.api.CatalogSync
import co.agrovision.network.api.ZoneRuleSync
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * La cola del productor y las reglas de zona son trabajos distintos: la primera
 * decide su reintento sin consultar las reglas, y las segundas reintentan solas.
 */
class SyncWorkersTest {

    private val context = mockk<Context>(relaxed = true)
    private val connectivity = mockk<ConnectivityMonitor>()
    private val settings = mockk<SettingsRepository>()

    /**
     * Caso de uso real sobre puertos simulados: `AgroResult` es una `value class`
     * y MockK no la devuelve bien desde una función `suspend` simulada.
     */
    private val syncPending = SyncPendingUseCase(
        captures = mockk(relaxed = true),
        reviews = mockk(relaxed = true),
        gateway = mockk(relaxed = true),
        account = mockk(relaxed = true),
        settings = settings,
        connectivity = connectivity,
        clock = { 0L },
        modelRegistry = mockk(relaxed = true),
    )
    private val purge = mockk<PurgeExpiredCapturesUseCase>()
    private val catalogSync = mockk<CatalogSync>()
    private val seeder = mockk<CatalogSeeder>()
    private val zoneRuleSync = mockk<ZoneRuleSync>()

    private fun params(attempt: Int) = mockk<WorkerParameters>(relaxed = true).also {
        every { it.runAttemptCount } returns attempt
    }

    private fun syncWorker(attempt: Int = 0): SyncWorker {
        coEvery { seeder.seedIfEmpty() } returns 0
        coEvery { catalogSync.sync() } returns true
        coEvery { purge() } returns 0
        return SyncWorker(context, params(attempt), syncPending, purge, catalogSync, seeder)
    }

    @Test
    fun `la cola que falla pide reintento sin consultar las reglas de zona`() = runTest {
        // Sin conexión: la cola devuelve `Failure(NoConnection)`.
        every { connectivity.current() } returns ConnectivityStatus(isOnline = false, isMetered = false)

        assertEquals(Result.retry(), syncWorker().doWork())

        // La cola resuelve primero; el catálogo va después.
        coVerifyOrder {
            connectivity.current()
            catalogSync.sync()
        }
        coVerify(exactly = 0) { zoneRuleSync.syncAll(any(), any()) }
    }

    @Test
    fun `SyncWorker no recibe la sincronizacion de reglas`() {
        val dependencies = SyncWorker::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertFalse(ZoneRuleSync::class.java in dependencies)
    }

    @Test
    fun `la cola agota sus intentos sin esperar a las reglas`() = runTest {
        every { connectivity.current() } returns ConnectivityStatus(isOnline = false, isMetered = false)

        assertEquals(Result.failure(), syncWorker(attempt = AgroConstants.Sync.MAX_ATTEMPTS).doWork())
        coVerify(exactly = 0) { zoneRuleSync.syncAll(any(), any()) }
    }

    @Test
    fun `una cola sin fallos termina con exito`() = runTest {
        // Red medida con «solo WiFi»: la cola se salta sin error (`Success`).
        every { connectivity.current() } returns ConnectivityStatus(isOnline = true, isMetered = true)
        coEvery { settings.current() } returns mockk {
            every { trainingConsent } returns true
            every { syncNetworkPolicy } returns SyncNetworkPolicy.SOLO_WIFI
        }

        assertEquals(Result.success(), syncWorker().doWork())
    }

    @Test
    fun `las reglas al dia terminan con exito`() = runTest {
        coEvery { zoneRuleSync.syncAll(any(), any()) } returns true

        assertEquals(Result.success(), ZoneRuleWorker(context, params(0), zoneRuleSync).doWork())
    }

    @Test
    fun `las reglas reintentan por su cuenta y con tope`() = runTest {
        coEvery { zoneRuleSync.syncAll(any(), any()) } returns false

        assertEquals(Result.retry(), ZoneRuleWorker(context, params(0), zoneRuleSync).doWork())
        assertEquals(Result.retry(), ZoneRuleWorker(context, params(1), zoneRuleSync).doWork())
        assertEquals(Result.failure(), ZoneRuleWorker(context, params(ZoneRuleWorker.MAX_ATTEMPTS - 1), zoneRuleSync).doWork())
        // Las reglas no tocan la cola del productor.
        verify(exactly = 0) { connectivity.current() }
    }
}
