package co.agrovision.core.common

import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier

/**
 * Despachadores inyectados, nunca referenciados como `Dispatchers.IO` desde el
 * código de producción. Una prueba que no puede sustituir el despachador es una
 * prueba que depende del planificador real y falla de forma intermitente.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val kind: AgroDispatcher)

enum class AgroDispatcher {
    /** Disco, base de datos y red. */
    IO,

    /** Cómputo puro: mapeos, preprocesado en JVM, cálculos de la compuerta 1. */
    Default,

    /** Hilo principal. Solo la capa de presentación. */
    Main,

    /**
     * Despachador dedicado de UN SOLO hilo para el intérprete de LiteRT (§7.3).
     *
     * No es una optimización: el intérprete no es seguro para uso concurrente.
     * Dos clasificaciones simultáneas sobre el mismo intérprete producen un
     * fallo en código nativo, no una excepción de Kotlin, y por tanto un cierre
     * inesperado sin traza aprovechable.
     */
    Inference,
}
