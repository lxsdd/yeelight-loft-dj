package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LampIdentificationTest {
    @Test fun whiteOnlyDeskLampDoesNotReceiveRgb() {
        val before = LampSnapshot(true, 42, 2, null, 3200, null, null)
        val plan = LampIdentification.prepare(before, setOf("get_prop", "set_power",
            "set_bright", "set_ct_abx"))
        assertEquals("Weißlicht", plan.kind)
        assertFalse(plan.pulse.any { it.method == "set_rgb" })
        assertEquals(listOf("set_ct_abx","set_bright"), plan.restore.map { it.method })
    }
    @Test fun unknownWhiteLampUsesBrightnessOnly() {
        val before = LampSnapshot(true, 80, 0, null, null, null, null)
        val plan = LampIdentification.prepare(before, setOf("set_power", "set_bright"))
        assertEquals("Helligkeit", plan.kind)
        assertEquals(18, plan.pulse.single().params[0])
        assertEquals(LampCommands.brightness(80), plan.restore.single())
    }
    @Test fun rgbLampRestoresItsExactColorAndBrightness() {
        val before = LampSnapshot(true, 33, 1, 0x3366AA, 3200, null, null)
        val plan = LampIdentification.prepare(before, setOf("set_power", "set_rgb", "set_bright"))
        assertTrue(plan.pulse.any { it.method == "set_rgb" })
        assertEquals(LampCommands.rgb(0x3366AA), plan.restore[0])
        assertEquals(LampCommands.brightness(33), plan.restore[1])
    }
    @Test fun initiallyOffLampAlwaysReturnsToOff() {
        val before = LampSnapshot(false, 75, 0, null, null, null, null)
        val plan = LampIdentification.prepare(before, setOf("set_power", "set_bright"))
        assertEquals(LampCommands.power(false), plan.restore.single())
    }
    @Test fun rejectsUnsafeIncompleteCapabilities() {
        val before = LampSnapshot(true, 50, 2, null, 3000, null, null)
        assertThrows(IllegalArgumentException::class.java) {
            LampIdentification.prepare(before, setOf("set_bright"))
        }
    }
}
