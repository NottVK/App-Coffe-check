package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroConstants
import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.SyncState
import co.agrovision.core.model.identity.UploadGate
import co.agrovision.core.model.port.AccountRepository
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.Clock
import co.agrovision.core.model.port.ConnectivityMonitor
import co.agrovision.core.model.port.ReviewRequestRepository
import co.agrovision.core.model.port.SettingsRepository
import co.agrovision.core.model.port.SyncGateway
import co.agrovision.core.model.port.UploadOutcome
import co.agrovision.core.model.port.UploadStatus
import co.agrovision.core.model.review.ReviewRequest
import kotlin.math.min
import kotlin.math.pow
import javax.inject.Inject

/**
 * Selecciona un lote por prioridad y antigüedad, lo sube y concilia estados.
 *
 * **Prioridad de cola (§9.4), y el orden importa:**
 *   1. Solicitudes de Revisión — son lo único que el productor está esperando
 *      que alguien responda. Retrasarlas alarga la latencia de resolución, que
 *      es el indicador anticipado del colapso del ciclo (§12.3).
 *   2. Correcciones del productor — dato de entrenamiento premium.
 *   3. Capturas confirmadas — valiosas en volumen, no urgentes una a una.
 *
 * Es idempotente de arriba abajo: cada elemento viaja con su UUID como clave de
 * idempotencia, y el estado `DUPLICATE` que devuelve el servidor no es un error
 * sino la confirmación de que ya estaba a salvo.
 */
class SyncPendingUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val reviews: ReviewRequestRepository,
    private val gateway: SyncGateway,
    private val account: AccountRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityMonitor,
    private val clock: Clock,
) {

    suspend operator fun invoke(trigger: SyncTrigger): AgroResult<SyncSummary, DomainError> {
        val status = connectivity.current()
        if (!status.isOnline) return AgroResult.failure(DomainError.NoConnection)

        // ADR-008: la decisión sobre el gasto de datos le corresponde a quien lo
        // paga. No se impone red no medida; se respeta lo que el productor eligió.
        val preferences = settings.current()
        if (status.isMetered && !preferences.syncNetworkPolicy.allowsMetered) {
            return AgroResult.success(SyncSummary.skipped(SkipReason.METERED_NETWORK))
        }

        // La compuerta se evalúa AQUÍ, al sincronizar, nunca al crear (§6.3).
        // Lo que está en cola no se pierde: espera a que la cuenta se valide.
        val current = account.current()
        val gate = UploadGate.evaluate(
            tier = current.tier,
            queuedCount = 0,
            registeredAtEpochMillis = current.registeredAtEpochMillis,
        )
        if (gate.isBlocked) {
            return AgroResult.success(SyncSummary.skipped(SkipReason.UPLOAD_GATE))
        }

        val now = clock.nowEpochMillis()
        var uploaded = 0
        var failed = 0

        // ── 1. Solicitudes de revisión ──
        val reviewBatch = reviews.nextBatchToUpload(AgroConstants.Sync.BATCH_SIZE, now)
        if (reviewBatch.isNotEmpty()) {
            when (val result = gateway.uploadReviewRequests(reviewBatch)) {
                is AgroResult.Failure -> failed += reviewBatch.size
                is AgroResult.Success -> {
                    uploaded += reconcileReviews(reviewBatch, result.value, now)
                }
            }
        }

        // ── 2 y 3. Correcciones y capturas confirmadas ──
        // `nextBatchToUpload` ya devuelve las correcciones primero: el orden vive
        // en la consulta SQL, no en un `sortedBy` en memoria sobre una lista que
        // podría tener miles de filas.
        val captureBatch = captures.nextBatchToUpload(AgroConstants.Sync.BATCH_SIZE, now)
        if (captureBatch.isNotEmpty()) {
            captureBatch.forEach { captures.updateSyncState(it.id, SyncState.UPLOADING) }
            when (val result = gateway.uploadCaptures(captureBatch)) {
                is AgroResult.Failure -> {
                    captureBatch.forEach { capture ->
                        failed++
                        captures.updateSyncState(
                            id = capture.id,
                            state = retryStateFor(capture.sync.attempts),
                            error = describe(result.error),
                            nextAttemptAtEpochMillis = now + backoffMillis(capture.sync.attempts),
                        )
                    }
                }
                is AgroResult.Success -> uploaded += reconcileCaptures(captureBatch, result.value, now)
            }
        }

        return AgroResult.success(
            SyncSummary(
                trigger = trigger,
                uploaded = uploaded,
                failed = failed,
                skipReason = null,
            )
        )
    }

    private suspend fun reconcileCaptures(
        batch: List<co.agrovision.core.model.capture.Capture>,
        outcomes: List<UploadOutcome>,
        now: Long,
    ): Int {
        val byId = outcomes.associateBy { it.localId }
        var uploaded = 0

        batch.forEach { capture ->
            when (val outcome = byId[capture.id]) {
                null -> captures.updateSyncState(
                    id = capture.id,
                    state = retryStateFor(capture.sync.attempts),
                    error = "El servidor no devolvió resultado para esta captura.",
                    nextAttemptAtEpochMillis = now + backoffMillis(capture.sync.attempts),
                )

                else -> when (outcome.status) {
                    UploadStatus.ACCEPTED -> {
                        outcome.presignedImageUrl?.let { url ->
                            gateway.uploadImage(url, capture.imagePath)
                        }
                        captures.updateSyncState(capture.id, SyncState.UPLOADED, outcome.serverId)
                        uploaded++
                    }
                    // Idempotencia funcionando: el servidor ya lo tenía.
                    UploadStatus.DUPLICATE -> {
                        captures.updateSyncState(capture.id, SyncState.UPLOADED, outcome.serverId)
                        uploaded++
                    }
                    // Rechazo definitivo: reintentar no cambiaría nada, y dejarla
                    // en PENDING la haría reintentarse para siempre.
                    UploadStatus.REJECTED -> captures.updateSyncState(
                        id = capture.id,
                        state = SyncState.FAILED,
                        error = outcome.rejectionReason,
                    )
                }
            }
        }
        return uploaded
    }

    private suspend fun reconcileReviews(
        batch: List<ReviewRequest>,
        outcomes: List<UploadOutcome>,
        now: Long,
    ): Int {
        val byId = outcomes.associateBy { it.localId }
        var uploaded = 0
        batch.forEach { review ->
            val outcome = byId[review.id] ?: return@forEach
            if (outcome.status == UploadStatus.REJECTED) return@forEach
            outcome.presignedImageUrl?.let { url ->
                review.images.forEach { gateway.uploadImage(url, it.imagePath) }
            }
            reviews.applyResolution(
                id = review.id,
                request = review.copy(
                    status = co.agrovision.core.model.review.ReviewStatus.SUBMITTED,
                    sync = review.sync.copy(
                        state = SyncState.UPLOADED,
                        serverId = outcome.serverId,
                    ),
                ),
            )
            uploaded++
        }
        return uploaded
    }

    /**
     * Tras agotar los reintentos la fila pasa a FAILED y deja de reintentarse en
     * automático — pero sigue visible y reintentable a mano. Una captura nunca
     * desaparece en silencio.
     */
    private fun retryStateFor(attempts: Int): SyncState =
        if (attempts + 1 >= AgroConstants.Sync.MAX_ATTEMPTS) SyncState.FAILED else SyncState.PENDING

    private fun backoffMillis(attempts: Int): Long {
        val base = AgroConstants.Sync.INITIAL_BACKOFF.inWholeMilliseconds
        val grown = base * 2.0.pow(attempts.coerceAtMost(MAX_BACKOFF_EXPONENT))
        return min(grown.toLong(), AgroConstants.Sync.MAX_BACKOFF.inWholeMilliseconds)
    }

    private fun describe(error: DomainError): String = when (error) {
        is DomainError.RemoteFailure -> "HTTP ${error.httpCode}: ${error.detail.orEmpty()}"
        DomainError.NoConnection -> "Sin conexión"
        else -> error::class.simpleName.orEmpty()
    }

    private companion object {
        const val MAX_BACKOFF_EXPONENT = 10
    }
}

enum class SyncTrigger {
    /** Disparador primario. Al abrir la app y al volver del segundo plano. Es la
     *  ruta que SIEMPRE funciona, independientemente del fabricante (§9.4). */
    FOREGROUND,

    /** Disparador secundario. Optimización: si el administrador de batería del
     *  fabricante lo suprime, no se pierde nada. */
    WORKER,

    /** Disparador terciario. Lotes grandes tras días sin señal, iniciado por una
     *  acción visible del productor. */
    FOREGROUND_SERVICE,

    MANUAL,
}

data class SyncSummary(
    val trigger: SyncTrigger,
    val uploaded: Int,
    val failed: Int,
    val skipReason: SkipReason?,
) {
    companion object {
        fun skipped(reason: SkipReason) = SyncSummary(SyncTrigger.MANUAL, 0, 0, reason)
    }
}

enum class SkipReason {
    METERED_NETWORK,
    UPLOAD_GATE,
    NOTHING_PENDING,
}
