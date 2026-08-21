package co.agrovision.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import co.agrovision.core.model.mlmodel.ModelSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puntero de ranura de modelo activa.
 *
 * **Este es el archivo más delicado del subsistema de modelos.** Escribir aquí
 * es el único punto de no retorno del procedimiento de actualización (§8.3): a
 * partir de la conmutación, la siguiente instanciación del intérprete usa el
 * modelo nuevo.
 *
 * DataStore da exactamente la garantía que hace falta: escritura atómica sobre
 * archivo temporal más renombrado. Un corte de energía a mitad deja el puntero
 * anterior intacto, nunca un puntero a medias.
 *
 * **ADR-013 — por qué no Proto DataStore.** El documento de arquitectura
 * especifica «DataStore Proto». Se implementa con un `Serializer` propio sobre
 * kotlinx.serialization en lugar de protobuf. Las garantías que importan
 * —tipado, atomicidad, transaccionalidad— son las de DataStore Core y son
 * idénticas en ambos casos; lo que aporta protobuf aquí es el plugin de Gradle
 * que descarga `protoc`, un binario nativo por plataforma que hace la
 * compilación menos reproducible sin resolver ningún problema que tengamos.
 */
@Singleton
class ModelSlotStore @Inject constructor(
    private val store: DataStore<ModelSlotPointer>,
) {

    val pointer: Flow<ModelSlotPointer> = store.data

    val activeSlot: Flow<ModelSlot> = store.data.map { it.activeSlot }

    suspend fun current(): ModelSlotPointer = store.data.first()

    /**
     * Conmutación atómica. Guarda la ranura anterior para que la reversión no
     * requiera descargar nada: el modelo previo sigue en disco (§8.4).
     */
    suspend fun activate(slot: ModelSlot, version: String, nowEpochMillis: Long) {
        store.updateData { previous ->
            ModelSlotPointer(
                activeSlot = slot,
                activeVersion = version,
                previousSlot = previous.activeSlot.takeIf { it != slot },
                previousVersion = previous.activeVersion,
                activatedAtEpochMillis = nowEpochMillis,
                lastCheckEpochMillis = previous.lastCheckEpochMillis,
                consecutiveFailures = 0,
            )
        }
    }

    /**
     * Reversión al modelo anterior. Sin descarga y sin red: por eso el objetivo
     * de «menos de 30 minutos entre detección de regresión y reversión efectiva»
     * es alcanzable.
     */
    suspend fun rollBack(): Boolean {
        var reverted = false
        store.updateData { previous ->
            val target = previous.previousSlot ?: return@updateData previous
            reverted = true
            ModelSlotPointer(
                activeSlot = target,
                activeVersion = previous.previousVersion,
                previousSlot = previous.activeSlot,
                previousVersion = previous.activeVersion,
                activatedAtEpochMillis = previous.activatedAtEpochMillis,
                lastCheckEpochMillis = previous.lastCheckEpochMillis,
                consecutiveFailures = 0,
            )
        }
        return reverted
    }

    suspend fun recordCheck(nowEpochMillis: Long) {
        store.updateData { it.copy(lastCheckEpochMillis = nowEpochMillis) }
    }

    suspend fun recordFailure() {
        store.updateData { it.copy(consecutiveFailures = it.consecutiveFailures + 1) }
    }
}

@kotlinx.serialization.Serializable
data class ModelSlotPointer(
    val activeSlot: ModelSlot = ModelSlot.A,
    val activeVersion: String? = null,
    val previousSlot: ModelSlot? = null,
    val previousVersion: String? = null,
    val activatedAtEpochMillis: Long = 0L,
    val lastCheckEpochMillis: Long = 0L,
    /** Fallos consecutivos de actualización. Alimenta el backoff y la alerta de
     *  «este dispositivo lleva N intentos sin poder actualizar». */
    val consecutiveFailures: Int = 0,
) {
    val standbySlot: ModelSlot get() = activeSlot.other
}

internal object ModelSlotPointerSerializer : Serializer<ModelSlotPointer> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = ModelSlotPointer()

    override suspend fun readFrom(input: InputStream): ModelSlotPointer =
        try {
            json.decodeFromString(
                ModelSlotPointer.serializer(),
                input.readBytes().decodeToString(),
            )
        } catch (error: SerializationException) {
            // Un puntero corrupto NO puede degradar en silencio a «ranura A»
            // como si nada: eso activaría un modelo que quizá no está instalado.
            // Lanzar hace que DataStore aplique su política de corrupción, que
            // restablece el valor por defecto de forma explícita y registrable.
            throw CorruptionException("Puntero de ranura de modelo ilegible", error)
        }

    override suspend fun writeTo(t: ModelSlotPointer, output: OutputStream) {
        output.write(
            json.encodeToString(ModelSlotPointer.serializer(), t).encodeToByteArray()
        )
    }
}
