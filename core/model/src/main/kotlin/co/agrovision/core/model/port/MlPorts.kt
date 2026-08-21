package co.agrovision.core.model.port

import co.agrovision.core.model.scan.GateDecision
import co.agrovision.core.model.scan.QualityAssessment
import co.agrovision.core.model.scan.RawInference
import co.agrovision.core.model.scan.RgbImage

/**
 * Las tres compuertas, expresadas como puertos del dominio.
 *
 * El dominio conoce el orden y el significado de la cadena; no conoce el
 * laplaciano, ni LiteRT, ni la distancia de Mahalanobis. Eso permite probar
 * `ClassifyImageUseCase` con compuertas falsas y probar cada compuerta contra
 * su matemática, por separado.
 */

/** Compuerta 1 — se evalúa ANTES de gastar cómputo en inferencia (§7.2). */
fun interface ImageQualityInspector {
    fun inspect(image: RgbImage): QualityAssessment
}

/**
 * Ejecución del modelo. Devuelve las dos cabezas: logits y embedding.
 *
 * El ciclo de vida es explícito a propósito. La memoria nativa de LiteRT no la
 * administra el recolector de basura de la JVM: omitir `release()` produce
 * fugas que en dispositivos de 2 GB terminan en cierre forzado (§7.3).
 */
interface PestClassifier {

    /**
     * Instancia el intérprete y ejecuta una inferencia con tensor vacío.
     *
     * Se llama al montar la pantalla de cámara. Sin precalentamiento, la primera
     * clasificación real tarda el doble o el triple que la estacionaria, y esa
     * primera es justamente la que forma la impresión del productor.
     */
    suspend fun warmUp()

    suspend fun classify(image: RgbImage): RawInference

    /** Versión del modelo en la ranura activa, o null si no hay ninguno válido. */
    fun activeModelVersion(): String?

    /** Libera la memoria nativa. Se invoca en `onCleared()` del ViewModel. */
    fun release()
}

/** Compuertas 2 y 3 — OOD sobre el embedding, y calibración sobre los logits. */
fun interface UncertaintyGate {
    fun evaluate(inference: RawInference): GateDecision
}
