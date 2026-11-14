package co.agrovision.core.designsystem.component

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HoldClickGateTest {

    @Test
    fun `toque corto da un paso`() {
        val gate = HoldClickGate()
        gate.onPress()
        assertTrue(gate.consumeClick())
    }

    @Test
    fun `el clic al soltar una pulsacion que repitio se descarta una sola vez`() {
        val gate = HoldClickGate()
        gate.onPress()
        gate.onRepeat()
        assertFalse(gate.consumeClick())
        // La siguiente activación (p. ej. TalkBack, sin pulsación) sí cuenta.
        assertTrue(gate.consumeClick())
    }

    @Test
    fun `una pulsacion cancelada no se come la siguiente activacion accesible`() {
        val gate = HoldClickGate()
        gate.onPress()
        gate.onRepeat()
        gate.onCancel() // dedo fuera del botón, o botón deshabilitado al llegar al extremo
        assertTrue(gate.consumeClick())
    }

    @Test
    fun `una nueva pulsacion empieza limpia`() {
        val gate = HoldClickGate()
        gate.onPress()
        gate.onRepeat()
        gate.onPress()
        assertTrue(gate.consumeClick())
    }
}
