package co.agrovision.core.model.zone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneScenariosTest {

    private val rule = ZoneRule(
        tipoEnfermedad = "roya",
        version = 1,
        calibrada = false,
        filas = listOf(
            ZoneRuleRow(orden = 1, sevMax = 10.0, incMax = 20.0, radioMinM = 0.0, radioMaxM = 3.0),
            ZoneRuleRow(orden = 2, sevMax = 30.0, incMax = 40.0, radioMinM = 3.0, radioMaxM = 8.0),
            ZoneRuleRow(orden = 3, sevMax = 60.0, incMax = 70.0, radioMinM = 8.0, radioMaxM = 15.0),
            ZoneRuleRow(orden = 4, sevMax = 100.0, incMax = 100.0, radioMinM = 15.0, radioMaxM = 30.0),
        ),
    )

    @Test
    fun `una fila por escenario con los numeros reales y la ultima como mas`() {
        val scenarios = rule.scenarios("roya")

        assertEquals(
            listOf(
                ZoneScenario(ZoneScenario.Kind.HASTA, "20", "10", "0", "3"),
                ZoneScenario(ZoneScenario.Kind.HASTA, "40", "30", "3", "8"),
                ZoneScenario(ZoneScenario.Kind.HASTA, "70", "60", "8", "15"),
                ZoneScenario(ZoneScenario.Kind.MAS, "100", "100", "15", "30"),
            ),
            scenarios,
        )
    }

    @Test
    fun `decimales con coma y sin punto cero`() {
        assertEquals("2,5", formatZoneNumber(2.5))
        assertEquals("15", formatZoneNumber(15.0))
        assertEquals("0", formatZoneNumber(0.0))
        assertEquals("12,3", formatZoneNumber(12.34))
    }

    @Test
    fun `una sola fila aplica en cualquier caso`() {
        val single = rule.copy(filas = listOf(ZoneRuleRow(1, 100.0, 100.0, 1.5, 4.0)))

        assertEquals(listOf(ZoneScenario(ZoneScenario.Kind.CUALQUIERA, "100", "100", "1,5", "4")), single.scenarios("roya"))
    }

    @Test
    fun `regla invalida o de otro tipo no da escenarios`() {
        assertTrue(rule.scenarios("phoma").isEmpty())
        assertTrue(rule.copy(filas = emptyList()).scenarios("roya").isEmpty())
        // Sin cobertura 100/100 en la última fila la tabla no cumple el contrato.
        assertTrue(rule.copy(filas = rule.filas.dropLast(1)).scenarios("roya").isEmpty())
    }
}
