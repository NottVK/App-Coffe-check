package co.agrovision.ml.inference

import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate

/**
 * Selección de acelerador: NNAPI → GPU → CPU (§7.3, patrón Strategy).
 *
 * La cadena existe porque **cada eslabón falla de verdad en dispositivos
 * reales**, no como precaución teórica:
 *
 * - **NNAPI** da la mejor latencia cuando funciona, pero su implementación la
 *   escribe el fabricante del SoC. Hay familias enteras donde falla al
 *   inicializar, y otras donde inicializa y devuelve resultados incorrectos en
 *   silencio. Además solo existe desde API 27, y este proyecto llega a API 22
 *   (ADR-012).
 * - **GPU** es más portable, pero en gama muy baja a veces no hay driver
 *   OpenCL/OpenGL utilizable.
 * - **CPU con cuatro hilos** siempre funciona. Es el suelo.
 *
 * Qué delegate acabó usándose se registra en telemetría, segmentado por familia
 * de dispositivo: es la única forma de descubrir que «los Tecno Spark 8 fallan
 * al inicializar NNAPI» antes de que lo reporte un usuario.
 *
 * ⚠️ Los delegates poseen memoria nativa. `close()` es obligatorio y va **después**
 * de cerrar el intérprete que los usa: al revés, el intérprete queda apuntando a
 * memoria liberada.
 */
sealed interface AcceleratorChoice {

    val telemetryName: String

    /** Recursos nativos que hay que cerrar después del intérprete. */
    fun close()

    class Nnapi(private val delegate: NnApiDelegate) : AcceleratorChoice {
        override val telemetryName = "nnapi"
        fun delegate(): NnApiDelegate = delegate
        override fun close() = delegate.close()
    }

    class Gpu(private val delegate: GpuDelegate) : AcceleratorChoice {
        override val telemetryName = "gpu"
        fun delegate(): GpuDelegate = delegate
        override fun close() = delegate.close()
    }

    data class Cpu(val threads: Int) : AcceleratorChoice {
        override val telemetryName = "cpu-$threads"
        override fun close() = Unit
    }
}

object DelegateStrategy {

    /**
     * Construye las opciones del intérprete probando la cadena en orden.
     *
     * Devuelve también el [AcceleratorChoice] elegido para que quien llama pueda
     * cerrarlo y registrarlo. No se cierra aquí: su vida es la del intérprete.
     */
    fun configure(preferred: AcceleratorPreference = AcceleratorPreference.AUTO): Configured {
        val options = Interpreter.Options()

        if (preferred != AcceleratorPreference.CPU_ONLY) {
            tryNnapi()?.let { accelerator ->
                options.addDelegate(accelerator.delegate())
                return Configured(options, accelerator)
            }
            tryGpu()?.let { accelerator ->
                options.addDelegate(accelerator.delegate())
                return Configured(options, accelerator)
            }
        }

        val threads = cpuThreads()
        options.setNumThreads(threads)
        options.setUseXNNPACK(true)
        return Configured(options, AcceleratorChoice.Cpu(threads))
    }

    private fun tryNnapi(): AcceleratorChoice.Nnapi? {
        // NNAPI no existe antes de API 27. Con minSdk 22 esto no es una
        // comprobación defensiva: es la ruta normal en parte de la flota.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null

        return runCatching { AcceleratorChoice.Nnapi(NnApiDelegate()) }
            .onFailure { Log.w(TAG, "NNAPI no disponible; se prueba GPU", it) }
            .getOrNull()
    }

    private fun tryGpu(): AcceleratorChoice.Gpu? = runCatching {
        val compatibility = CompatibilityList()
        if (!compatibility.isDelegateSupportedOnThisDevice) return null
        // `GpuDelegate()` sin argumentos y no `bestOptionsForThisDevice`: ese
        // devuelve un `GpuDelegateFactory.Options` que vive en un artefacto
        // aparte (`litert-gpu-api`). Traerlo solo para ajustar opciones que ya
        // tienen valores por defecto razonables no compensa: son dependencias
        // extra en un módulo cuya lista corta es una propiedad que se cuida.
        AcceleratorChoice.Gpu(GpuDelegate())
    }.onFailure { Log.w(TAG, "GPU no disponible; se usa CPU", it) }.getOrNull()

    /**
     * Cuatro hilos como tope, no `availableProcessors()`.
     *
     * En un octa-core de gama baja los cuatro núcleos «grandes» suelen ser
     * idénticos a los pequeños y saturar los ocho calienta el teléfono,
     * dispara el throttling térmico y acaba dando **peor** latencia sostenida
     * que cuatro.
     */
    private fun cpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_CPU_THREADS)

    class Configured(
        val options: Interpreter.Options,
        val accelerator: AcceleratorChoice,
    )

    private const val TAG = "AgroVision.Delegate"
    private const val MAX_CPU_THREADS = 4
}

enum class AcceleratorPreference {
    AUTO,

    /**
     * Forzar CPU. Se expone en la pantalla de diagnóstico: cuando un dispositivo
     * da resultados extraños, poder descartar el delegate sin publicar una
     * versión nueva es la diferencia entre diagnosticar en una tarde o en un mes.
     */
    CPU_ONLY,
}
