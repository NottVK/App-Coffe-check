package co.agrovision.ml.inference.di

import co.agrovision.core.model.port.ImageQualityInspector
import co.agrovision.core.model.port.PestClassifier
import co.agrovision.core.model.port.UncertaintyGate
import co.agrovision.ml.inference.DelegatingUncertaintyGate
import co.agrovision.ml.inference.InferenceTelemetryListener
import co.agrovision.ml.inference.LiteRtPestClassifier
import co.agrovision.ml.preprocessing.LaplacianQualityInspector
import co.agrovision.ml.preprocessing.QualityThresholds
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Las tres compuertas, cableadas.
 *
 * El orden en que las usa `ClassifyImageUseCase` —calidad, OOD, calibración— vive
 * en el dominio. Aquí solo se dice qué implementación cumple cada puerto.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InferenceBindings {

    @Binds
    @Singleton
    abstract fun bindPestClassifier(impl: LiteRtPestClassifier): PestClassifier

    @Binds
    @Singleton
    abstract fun bindUncertaintyGate(impl: DelegatingUncertaintyGate): UncertaintyGate
}

@Module
@InstallIn(SingletonComponent::class)
object InferenceProviders {

    /**
     * Compuerta 1. Se provee en vez de vincularse porque el constructor tiene un
     * parámetro con valor por defecto, y Dagger no sabe leer valores por defecto
     * de Kotlin: intentaría inyectar `QualityThresholds` y no encontraría binding.
     *
     * Los umbrales quedan explícitos aquí, que además es donde tocará cambiarlos
     * cuando la Fase 0 los calibre contra imágenes reales etiquetadas por
     * agrónomo.
     */
    @Provides
    @Singleton
    fun provideQualityInspector(): ImageQualityInspector =
        LaplacianQualityInspector(QualityThresholds.DEFAULT)

    /**
     * Telemetría de inferencia.
     *
     * ⚠️ Pendiente de conectar a Crashlytics. Mientras tanto no hace nada, lo que
     * significa que **todavía no sabemos qué delegate acaba usando cada familia
     * de dispositivo** — y ese dato es justo el que permite descubrir que cierto
     * modelo de teléfono falla al inicializar NNAPI antes de que lo reporte un
     * usuario (§19.3).
     */
    @Provides
    @Singleton
    fun provideInferenceTelemetry(): InferenceTelemetryListener = InferenceTelemetryListener.NoOp
}
