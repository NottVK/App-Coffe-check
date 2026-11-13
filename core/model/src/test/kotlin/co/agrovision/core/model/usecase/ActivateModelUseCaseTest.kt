package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.mlmodel.CalibrationParams
import co.agrovision.core.model.mlmodel.InstalledModel
import co.agrovision.core.model.mlmodel.ModelChannel
import co.agrovision.core.model.mlmodel.ModelManifest
import co.agrovision.core.model.mlmodel.ModelRegistryState
import co.agrovision.core.model.mlmodel.ModelSlot
import co.agrovision.core.model.port.ModelRegistryRepository
import co.agrovision.core.model.port.TelemetryRecorder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * La búsqueda manual, el worker y el sondeo de primer plano pueden pedir la misma
 * actualización a la vez. Solo una instalación puede estar en curso, y la que
 * esperaba no reinstala lo que la primera ya dejó activo.
 */
class ActivateModelUseCaseTest {

    @Test
    fun `dos activaciones simultaneas de la misma version instalan una sola vez`() = runTest {
        val registry = FakeRegistry()
        val activate = ActivateModelUseCase(registry, mockk<TelemetryRecorder>(relaxed = true))

        val first = async { activate(manifest("2.0.0")) }
        val second = async { activate(manifest("2.0.0")) }
        yield()
        registry.release.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, registry.installs)
        assertEquals(1, registry.maxConcurrentInstalls)
    }

    /** A mano porque MockK no sabe devolver `AgroResult` genérico. */
    private class FakeRegistry : ModelRegistryRepository {
        val release = CompletableDeferred<Unit>()
        var installs = 0
        var maxConcurrentInstalls = 0
        private var inFlight = 0
        private var activeVersion: String? = "1.0.0"

        override fun observeState(): Flow<ModelRegistryState> = flowOf()
        override suspend fun currentState() = ModelRegistryState(
            activeSlot = ModelSlot.A,
            active = activeVersion?.let(::installed),
            standby = null,
            lastCheckEpochMillis = 0L,
            lastFailure = null,
        )
        override suspend fun fetchManifest(): AgroResult<ModelManifest?, DomainError> = error("sin uso")

        override suspend fun installToStandby(manifest: ModelManifest): AgroResult<InstalledModel, DomainError> {
            installs++
            inFlight++
            maxConcurrentInstalls = maxOf(maxConcurrentInstalls, inFlight)
            release.await()
            inFlight--
            return AgroResult.success(installed(manifest.version).copy(slot = ModelSlot.B))
        }

        override suspend fun activate(slot: ModelSlot): AgroResult<Unit, DomainError> {
            activeVersion = "2.0.0"
            return AgroResult.unit
        }

        override suspend fun rollBack(): AgroResult<Unit, DomainError> = AgroResult.unit
        override suspend fun activeCalibration(): CalibrationParams? = null
        override suspend fun recordCheck(nowEpochMillis: Long) = Unit
    }

    private companion object {
        fun manifest(version: String) = ModelManifest(
            version = version,
            channel = ModelChannel.PRODUCTION,
            minAppVersion = "1.0.0",
            sizeBytes = 1_000L,
            sha256 = "ab".repeat(32),
            signatureBase64 = "firma",
            modelUrl = "m",
            labelsUrl = "l",
            calibrationUrl = "c",
            releaseNotes = "",
            killSwitch = false,
        )

        fun installed(version: String) = InstalledModel(
            slot = ModelSlot.A,
            version = version,
            channel = ModelChannel.PRODUCTION,
            sha256 = "ab".repeat(32),
            classCount = 1,
            embeddingDim = 1,
            labels = emptyList(),
            calibration = CalibrationParams(
                temperature = 1f,
                confidenceThreshold = 0.7f,
                marginThreshold = 0.1f,
                oodThreshold = 2f,
                energyWeight = 0.5f,
                classCentroids = listOf(floatArrayOf(0f)),
                precisionMatrix = floatArrayOf(1f),
                energyMean = 0f,
                energyStdDev = 1f,
            ),
            installedAtEpochMillis = 0L,
        )
    }
}
