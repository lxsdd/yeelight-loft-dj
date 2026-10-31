package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LampManualTest {
    private fun snapshot(vararg properties: String): LampSnapshot =
        LampSnapshot.fromValues(properties.toList()) ?: error("Invalid fixture")

    @Test fun acceptsValidLampCommands() {
        assertEquals("set_power", LampCommands.power(false).method)
        assertEquals(listOf("off", "smooth", 300), LampCommands.power(false).params)
        assertEquals(listOf(73, "smooth", 300), LampCommands.brightness(73).params)
        assertEquals(listOf(0x22AADD, "smooth", 300), LampCommands.rgb(0x22AADD).params)
        assertEquals(listOf(4000, "smooth", 300), LampCommands.temperature(4000).params)
        assertEquals(listOf(210, 85, "smooth", 300), LampCommands.hsv(210, 85).params)
    }

    @Test fun rejectsOutOfRangeCommands() {
        for (n in listOf(0, 101)) assertThrows(IllegalArgumentException::class.java) { LampCommands.brightness(n) }
        for (n in listOf(1699, 6501)) assertThrows(IllegalArgumentException::class.java) { LampCommands.temperature(n) }
        assertThrows(IllegalArgumentException::class.java) { LampCommands.rgb(-1) }
        assertThrows(IllegalArgumentException::class.java) { LampCommands.rgb(0x1000000) }
        assertThrows(IllegalArgumentException::class.java) { LampCommands.hsv(360, 5) }
        assertThrows(IllegalArgumentException::class.java) { LampCommands.hsv(10, -1) }
    }

    @Test fun snapshotParsesAllProperties() {
        val s = snapshot("on", "65", "2", "16711680", "2800", "150", "80")
        assertTrue(s.isOn)
        assertEquals(65, s.brightness)
        assertEquals(2800, s.temperature)
        assertEquals(2, s.colorMode)
        assertEquals(7, LampSnapshot.properties.size)
    }

    @Test fun snapshotRejectsMissingCriticalState() {
        assertNull(LampSnapshot.fromValues(listOf("on")))
        assertNull(LampSnapshot.fromValues(listOf("maybe", "50")))
        assertNull(LampSnapshot.fromValues(listOf("on", "0")))
        assertNull(LampSnapshot.fromValues(listOf("on", "999")))
    }

    @Test fun restoreOffLampDoesNotLeaveLightOn() {
        val s = snapshot("off", "30", "1", "16711680", "3000", "", "")
        assertEquals(listOf(LampCommands.power(false)), s.restoreCommands())
    }

    @Test fun restoreRgbLampPreservesColorAndBrightness() {
        val s = snapshot("on", "42", "1", "16711680", "3000", "", "")
        assertEquals(listOf(LampCommands.rgb(16711680), LampCommands.brightness(42)), s.restoreCommands())
    }

    @Test fun restoreWhiteAndHsvLamps() {
        val white = snapshot("on", "70", "2", "16777215", "3500", "", "")
        val hsv = snapshot("on", "88", "3", "", "", "123", "45")
        assertEquals("set_ct_abx", white.restoreCommands()[0].method)
        assertEquals("set_hsv", hsv.restoreCommands()[0].method)
        assertEquals(listOf(123, 45, "smooth", 300), hsv.restoreCommands()[0].params)
    }

    @Test fun unknownModeStillRestoresBrightness() {
        val s = snapshot("on", "42", "not-reported")
        assertEquals(listOf(LampCommands.brightness(42)), s.restoreCommands())
    }
}
