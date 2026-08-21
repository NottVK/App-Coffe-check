package co.agrovision.core.model.usecase

import co.agrovision.core.common.AgroResult
import co.agrovision.core.common.onSuccess
import co.agrovision.core.model.DomainError
import co.agrovision.core.model.port.CaptureRepository
import co.agrovision.core.model.port.TelemetryRecorder
import javax.inject.Inject

/**
 * El productor confirma que la predicción era correcta.
 *
 * Parece un gesto menor y es la mitad de la métrica de exactitud de campo, la
 * única medida de exactitud sobre la distribución real (§12.3). La validación
 * en laboratorio dice cómo se comporta el modelo con las fotos que ya teníamos;
 * esto dice cómo se comporta con las que el productor toma hoy.
 */
class ConfirmPredictionUseCase @Inject constructor(
    private val captures: CaptureRepository,
) {
    suspend operator fun invoke(captureId: String): AgroResult<Unit, DomainError> =
        captures.confirmPrediction(captureId)
}

/**
 * El productor corrige una predicción errónea.
 *
 * Es la otra mitad de la exactitud de campo y, sobre todo, dato de
 * entrenamiento premium: una muestra real, en condiciones reales, con la
 * etiqueta correcta y con la constancia de que el modelo falló ahí.
 *
 * Un aumento sostenido de la tasa de corrección por encima del 20 % frente a la
 * versión anterior es uno de los cuatro indicadores que disparan la reversión
 * del modelo (§8.4), y por eso se registra en telemetría con la versión que
 * produjo el fallo.
 */
class CorrectPredictionUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val telemetry: TelemetryRecorder,
) {
    suspend operator fun invoke(
        captureId: String,
        correctedClassId: String,
    ): AgroResult<Unit, DomainError> {
        val capture = captures.findById(captureId)
            ?: return AgroResult.failure(DomainError.NotFound("Capture", captureId))

        return captures.correctPrediction(captureId, correctedClassId).onSuccess {
            telemetry.recordCorrection(
                modelVersion = capture.modelVersion,
                fromClassId = capture.predictedClassId,
                toClassId = correctedClassId,
            )
        }
    }
}
