package co.agrovision.core.datastore

import androidx.datastore.core.DataStore
import co.agrovision.core.model.mlmodel.ModelSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelSlotStoreTest {

    private class MemoryStore(initial: ModelSlotPointer) : DataStore<ModelSlotPointer> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<ModelSlotPointer> = state
        override suspend fun updateData(transform: suspend (t: ModelSlotPointer) -> ModelSlotPointer): ModelSlotPointer {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private fun store(initial: ModelSlotPointer = ModelSlotPointer()) = ModelSlotStore(MemoryStore(initial))

    @Test
    fun `la primera activacion no deja como anterior una ranura vacia`() = runTest {
        val slots = store()

        slots.activate(ModelSlot.B, "1.3.2", nowEpochMillis = 10L)

        val pointer = slots.current()
        assertEquals(ModelSlot.B, pointer.activeSlot)
        assertNull(pointer.previousSlot)
        assertNull(pointer.previousVersion)
    }

    @Test
    fun `sin modelo anterior la reversion no cambia de ranura`() = runTest {
        val slots = store()
        slots.activate(ModelSlot.B, "1.3.2", nowEpochMillis = 10L)

        val reverted = slots.rollBack()

        assertFalse(reverted)
        assertEquals(ModelSlot.B, slots.current().activeSlot)
        assertEquals("1.3.2", slots.current().activeVersion)
    }

    @Test
    fun `un puntero guardado con ranura anterior vacia tampoco revierte`() = runTest {
        // Estado real encontrado en un teléfono antes de la corrección
        val slots = store(
            ModelSlotPointer(activeSlot = ModelSlot.B, activeVersion = "1.3.2", previousSlot = ModelSlot.A),
        )

        assertFalse(slots.rollBack())
        assertEquals(ModelSlot.B, slots.current().activeSlot)
    }

    @Test
    fun `con dos versiones instaladas la reversion vuelve a la anterior`() = runTest {
        val slots = store()
        slots.activate(ModelSlot.B, "1.3.2", nowEpochMillis = 10L)
        slots.activate(ModelSlot.A, "1.4.0", nowEpochMillis = 20L)

        assertTrue(slots.rollBack())

        val pointer = slots.current()
        assertEquals(ModelSlot.B, pointer.activeSlot)
        assertEquals("1.3.2", pointer.activeVersion)
    }
}
