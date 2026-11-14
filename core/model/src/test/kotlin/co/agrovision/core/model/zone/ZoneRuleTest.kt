package co.agrovision.core.model.zone

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZoneRuleTest {

    // Filas desordenadas a propósito: la regla se aplica por `orden`, no por posición.
    private val rule = ZoneRule(
        tipoEnfermedad = "roya",
        version = 3,
        calibrada = false,
        filas = listOf(
            ZoneRuleRow(orden = 3, sevMax = 60.0, incMax = 70.0, radioMinM = 8.0, radioMaxM = 15.0),
            ZoneRuleRow(orden = 1, sevMax = 10.0, incMax = 20.0, radioMinM = 0.0, radioMaxM = 3.0),
            ZoneRuleRow(orden = 4, sevMax = 100.0, incMax = 100.0, radioMinM = 15.0, radioMaxM = 30.0),
            ZoneRuleRow(orden = 2, sevMax = 30.0, incMax = 40.0, radioMinM = 3.0, radioMaxM = 8.0),
        ),
    )

    @Test
    fun `ejemplo del contrato sev 70 inc 55 a 5000 plantas por hectarea`() {
        val estimate = estimateZone(rule, severityPct = 70.0, incidencePct = 55.0, plantsPerHa = 5000)!!

        assertEquals(4, estimate.filaAplicada.orden)
        assertEquals(15.0, estimate.radioMinM)
        assertEquals(30.0, estimate.radioMaxM)
        assertEquals(353, estimate.plantasMin)
        assertEquals(1414, estimate.plantasMax)
        assertFalse(estimate.calibrada)
        assertEquals(3, estimate.reglaVersion)
    }

    @Test
    fun `los limites son inclusivos`() {
        assertEquals(1, estimateZone(rule, 10.0, 20.0, 5000)!!.filaAplicada.orden)
        assertEquals(2, estimateZone(rule, 10.01, 20.0, 5000)!!.filaAplicada.orden)
        assertEquals(2, estimateZone(rule, 10.0, 20.01, 5000)!!.filaAplicada.orden)
        assertEquals(3, estimateZone(rule, 60.0, 70.0, 5000)!!.filaAplicada.orden)
    }

    @Test
    fun `exige severidad Y incidencia en la misma fila`() {
        // Severidad baja pero incidencia alta: no basta con cumplir una de las dos.
        assertEquals(3, estimateZone(rule, 5.0, 65.0, 5000)!!.filaAplicada.orden)
    }

    @Test
    fun `si ninguna fila cumple se usa la ultima por orden`() {
        val partial = rule.copy(filas = rule.filas.filter { it.orden != 4 })

        val estimate = estimateZone(partial, 95.0, 95.0, 5000)!!

        assertEquals(3, estimate.filaAplicada.orden)
        assertEquals(8.0, estimate.radioMinM)
    }

    @Test
    fun `regla sin filas no produce zona`() {
        assertNull(estimateZone(rule.copy(filas = emptyList()), 50.0, 50.0, 5000))
    }

    @Test
    fun `plantas por radio redondean al entero mas cercano`() {
        assertEquals(0, plantsInRadius(0.0, 5000))
        assertEquals(14, plantsInRadius(3.0, 5000)) // 14,137...
        assertEquals(101, plantsInRadius(8.0, 5000)) // 100,53...
        assertEquals(283, plantsInRadius(30.0, 1000)) // 282,74...
    }

    @Test
    fun `la regla calibrada se informa como tal`() {
        assertTrue(estimateZone(rule.copy(calibrada = true), 1.0, 1.0, 5000)!!.calibrada)
    }

    @Test
    fun `clases con zona por classId y por pestId`() {
        assertEquals("roya", ZoneRuleTypes.forClass("Leaf rust"))
        assertEquals("cercospora", ZoneRuleTypes.forClass("Cerscospora"))
        assertEquals("phoma", ZoneRuleTypes.forClass("Phoma"))
        assertEquals("antracnosis", ZoneRuleTypes.forClass("Enfermedad_Antracnosis"))
        assertEquals("minador", ZoneRuleTypes.forClass("Miner"))
        assertEquals("hormigas", ZoneRuleTypes.forClass("Plaga_Hormigas"))
        assertEquals("roya", ZoneRuleTypes.forClass(null, "Enfermedad_Roya"))
        assertEquals("cercospora", ZoneRuleTypes.forClass("desconocida", "Enfermedad_ManchadeHierro"))
        assertEquals("minador", ZoneRuleTypes.forClass(null, "Plaga_Minador"))
    }

    @Test
    fun `deficiencias hoja sana y rechazos no tienen zona`() {
        assertNull(ZoneRuleTypes.forClass("Healthy"))
        assertNull(ZoneRuleTypes.forClass("Deficiencia_Potasio", "Deficiencia_Potasio"))
        assertNull(ZoneRuleTypes.forClass("Deficiencia_Zinc", null))
        assertNull(ZoneRuleTypes.forClass(null, null))
        // Ojo de gallo está en el catálogo pero no tiene regla de zona.
        assertNull(ZoneRuleTypes.forClass(null, "Enfermedad_OjodeGallo"))
    }

    @Test
    fun `valores representativos del selector de severidad`() {
        assertEquals(listOf(5.0, 20.0, 45.0, 75.0), SeverityBand.entries.map { it.representativePct })
    }
}
