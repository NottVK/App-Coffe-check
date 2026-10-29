package co.agrovision.feature.scan

import co.agrovision.core.common.AgroResult
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.capture.Capture
import co.agrovision.core.model.capture.GeoPoint
import co.agrovision.core.model.capture.LocationPrecision
import co.agrovision.core.model.pest.CropType
import co.agrovision.core.model.pest.PlantOrgan
import co.agrovision.core.model.port.FixResult
import co.agrovision.core.model.review.CaptureAngle
import co.agrovision.core.model.review.ReviewImage
import co.agrovision.core.model.scan.ClassificationResult
import co.agrovision.core.model.scan.RgbImage
import co.agrovision.core.model.settings.UserPreferences
import co.agrovision.core.model.usecase.CreateReviewRequestUseCase
import co.agrovision.core.model.usecase.LocationShareConsentUseCase
import co.agrovision.core.model.usecase.SaveCaptureUseCase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * La solicitud de revisión viaja con la ubicación EXACTA aunque el productor haya
 * elegido vereda para sus capturas, y solo con su consentimiento en contexto.
 *
 * Se construyen los casos de uso reales sobre los falsos de `ScanFakes`: lo que se
 * comprueba es lo que de verdad quedaría guardado y se enviaría.
 */
class LocationSharingTest {

    /** Fix con altitud y marcado como simulado: ambos tienen que sobrevivir. */
    private val fieldFix = GeoPoint(
        latitude = 2.92734,
        longitude = -75.28197,
        accuracyMeters = 6f,
        altitudeMeters = 1_432.5,
        isMock = true,
    )

    private val captures = FakeCaptureRepository()
    private val reviews = FakeReviewRepository()
    private val gps = FakeLocationProvider(fieldFix)

    private fun settings(
        precision: LocationPrecision = LocationPrecision.VEREDA,
        consentAt: Long? = CONSENT_AT,
    ) = FakeSettings(
        UserPreferences.DEFAULT.copy(
            trainingConsent = true,
            locationEnabled = true,
            locationPrecision = precision,
            locationShareConsentAtEpochMillis = consentAt,
        )
    )

    private fun saveCapture(settings: FakeSettings = settings()) = SaveCaptureUseCase(
        captures = captures,
        images = FakeImageStore(),
        location = gps,
        settings = settings,
        clock = FixedClock(),
        ids = SequentialIds(),
    )

    private fun createReview(settings: FakeSettings = settings()) = CreateReviewRequestUseCase(
        requests = reviews,
        captures = captures,
        location = gps,
        settings = settings,
        clock = FixedClock(),
        ids = SequentialIds("solicitud-"),
    )

    private suspend fun savedCapture(settings: FakeSettings = settings()): Capture {
        val result = saveCapture(settings)(
            SaveCaptureUseCase.Request(
                image = RgbImage(1, 1, intArrayOf(0)),
                result = ClassificationResult.OutOfDomain(
                    oodScore = 9f,
                    modelVersion = "2.0.0",
                    latencyMillis = 10L,
                ),
                embedding = null,
                cropType = CropType.CAFE,
                plantOrgan = PlantOrgan.HOJA,
            )
        )
        return (result as AgroResult.Success).value
    }

    private fun request(captureId: String?, confirmedStillAtLot: Boolean = false) =
        CreateReviewRequestUseCase.Request(
            captureId = captureId,
            images = listOf(photo("a"), photo("b")),
            cropType = CropType.CAFE,
            plantOrgan = PlantOrgan.HOJA,
            noteText = null,
            noteAudioPath = null,
            confirmedStillAtLot = confirmedStillAtLot,
        )

    // ── La captura conserva la exacta ────────────────────────────────────────

    @Test
    fun `una captura con VEREDA conserva la coordenada exacta en el dispositivo`() = runTest {
        val capture = savedCapture(settings(LocationPrecision.VEREDA))

        val stored = captures.saved.single().location
        assertEquals(capture.location, stored)
        assertEquals(fieldFix.latitude, stored?.latitude)
        assertEquals(fieldFix.longitude, stored?.longitude)
        assertEquals(true, stored?.isExact)
    }

    @Test
    fun `la preferencia VEREDA redondea solo lo que se envia, no lo guardado`() = runTest {
        val stored = savedCapture(settings(LocationPrecision.VEREDA)).location!!

        val outgoing = stored.withPrecision(LocationPrecision.VEREDA)

        assertEquals(2.93, outgoing.latitude, 1e-9)
        assertEquals(-75.28, outgoing.longitude, 1e-9)
        assertEquals(false, outgoing.isExact)
        assertNull(outgoing.altitudeMeters)
        assertEquals(fieldFix.latitude, captures.saved.single().location?.latitude)
    }

    // ── La solicitud fuerza exacta ───────────────────────────────────────────

    @Test
    fun `la solicitud usa la coordenada exacta de la captura aunque la preferencia sea VEREDA`() = runTest {
        val prefs = settings(LocationPrecision.VEREDA)
        val capture = savedCapture(prefs)

        val review = (createReview(prefs)(request(capture.id)) as AgroResult.Success).value

        val location = requireNotNull(review.location)
        assertEquals(fieldFix.latitude, location.latitude)
        assertEquals(fieldFix.longitude, location.longitude)
        assertTrue(location.isExact)
        assertEquals(0, gps.fixRequests, "con la coordenada exacta de la foto no hace falta un GPS nuevo")
        assertEquals(review, reviews.saved.single())
    }

    @Test
    fun `una solicitud directa toma un fix nuevo exacto aunque la preferencia sea VEREDA`() = runTest {
        val result = createReview(settings(LocationPrecision.VEREDA))(request(captureId = null))
        val review = (result as AgroResult.Success).value

        assertEquals(1, gps.fixRequests)
        assertEquals(fieldFix.latitude, review.location?.latitude)
        assertEquals(true, review.location?.isExact)
    }

    @Test
    fun `altitud y fix simulado viajan hasta la solicitud`() = runTest {
        val prefs = settings(LocationPrecision.VEREDA)
        val capture = savedCapture(prefs)

        val review = (createReview(prefs)(request(capture.id)) as AgroResult.Success).value

        assertEquals(1_432.5, review.location?.altitudeMeters)
        assertEquals(true, review.location?.isMock)
    }

    // ── Consentimiento ───────────────────────────────────────────────────────

    @Test
    fun `sin consentimiento no se lee el GPS ni se guarda nada`() = runTest {
        val result = createReview(settings(consentAt = null))(request(captureId = null))

        assertEquals(DomainError.LocationConsentRequired, (result as AgroResult.Failure).error)
        assertTrue(reviews.saved.isEmpty())
        assertEquals(0, gps.fixRequests)
    }

    @Test
    fun `aceptar guarda la fecha y revocar la borra`() = runTest {
        val prefs = settings(consentAt = null)
        val consent = LocationShareConsentUseCase(prefs, FixedClock(NOW))

        assertEquals(false, consent.isGranted())
        consent.grant()
        assertEquals(NOW, prefs.current().locationShareConsentAtEpochMillis)
        assertEquals(true, consent.isGranted())
        consent.revoke()
        assertNull(prefs.current().locationShareConsentAtEpochMillis)
    }

    // ── Fallos de GPS ────────────────────────────────────────────────────────

    @Test
    fun `sin GPS no se envia nada`() = runTest {
        gps.fix = FixResult.GpsDisabled

        val result = createReview()(request(captureId = null))

        assertEquals(DomainError.GpsDisabled, (result as AgroResult.Failure).error)
        assertTrue(reviews.saved.isEmpty())
    }

    @Test
    fun `sin permiso o sin fix a tiempo tampoco se guarda la solicitud`() = runTest {
        gps.fix = FixResult.PermissionDenied
        val denied = createReview()(request(captureId = null))
        assertEquals(DomainError.LocationPermissionDenied, (denied as AgroResult.Failure).error)

        gps.fix = FixResult.TimedOut
        val timedOut = createReview()(request(captureId = null))
        assertEquals(DomainError.LocationFixTimeout, (timedOut as AgroResult.Failure).error)

        assertTrue(reviews.saved.isEmpty())
    }

    // ── Capturas antiguas ────────────────────────────────────────────────────

    @Test
    fun `una captura antigua sin coordenada exacta pide confirmar y no manda la aproximada`() = runTest {
        captures.save(legacyCapture())

        val result = createReview()(request("antigua"))

        assertEquals(DomainError.LocationNeedsConfirmation, (result as AgroResult.Failure).error)
        assertTrue(reviews.saved.isEmpty())
        assertEquals(0, gps.fixRequests)
    }

    @Test
    fun `confirmada la presencia en el lote se usa un fix nuevo y no la coordenada vieja`() = runTest {
        captures.save(legacyCapture())

        val result = createReview()(request("antigua", confirmedStillAtLot = true))
        val review = (result as AgroResult.Success).value

        assertEquals(1, gps.fixRequests)
        assertEquals(fieldFix.latitude, review.location?.latitude)
        assertEquals(true, review.location?.isExact)
    }

    /** Captura de antes de la migración: coordenada de origen incierto. */
    private suspend fun legacyCapture(): Capture {
        val base = savedCapture()
        captures.saved.clear()
        return base.copy(
            id = "antigua",
            location = GeoPoint(2.93, -75.28, accuracyMeters = null, isExact = false),
        )
    }

    private fun photo(id: String) = ReviewImage(
        id = id,
        imagePath = "/fotos/$id.jpg",
        thumbnailPath = "/fotos/$id.thumb.jpg",
        angle = CaptureAngle.GENERAL,
        embedding = null,
    )

    private companion object {
        const val CONSENT_AT = 1_690_000_000_000L
        const val NOW = 1_700_000_000_000L
    }
}
