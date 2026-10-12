package dev.pawfect.addons.features.rng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RngMeterTest {

    @Test
    fun meterNames() {
        assertEquals("F7", RngMeter.meterKey("Catacombs (F7)"))
        assertEquals("M3", RngMeter.meterKey("Catacombs (M3)"))
        assertEquals("F6", RngMeter.meterKey("§cCatacombs - Floor 6"))
        assertEquals("M6", RngMeter.meterKey("Catacombs - Master Mode Floor 6"))
        assertEquals("F7", RngMeter.meterKey("Floor VII"))
        assertEquals("Voidgloom Seraph", RngMeter.meterKey("§5Voidgloom Seraph"))
        assertNull(RngMeter.meterKey("Catacombs"))
        assertNull(RngMeter.meterKey("Experimentation Table"))
    }

    @Test
    fun amounts() {
        assertEquals(31_800L, RngMeter.amount("31,800"))
        assertEquals(31_800L, RngMeter.amount("31.8k"))
        assertEquals(1_200_000L, RngMeter.amount("1.2M"))
        assertNull(RngMeter.amount("?"))
    }
}
