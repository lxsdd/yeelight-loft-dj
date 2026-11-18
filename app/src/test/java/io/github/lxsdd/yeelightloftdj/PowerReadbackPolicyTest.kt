package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class PowerReadbackPolicyTest {
    private fun device(model: String, methods: Set<String>) =
        YeelightDevice("test", "192.168.0.134", model = model, methods = methods)

    @Test fun color4NeverAutomaticallyPollsAfterManualPower() {
        val methods = setOf("get_prop", "set_power", "set_rgb")
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("color4", methods)))
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("yeelink.light.color4", methods)))
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("Yeelink.Light.COLOR4", methods)))
    }

    @Test fun unidentifiedManuallyAddedBulbNeverSpeculativelyPollsAfterPower() {
        val methods = setOf("get_prop", "set_power")
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("unknown", methods)))
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("Yeelight (LAN)", methods)))
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device("", methods)))
    }

    @Test fun otherModelsRequireAdvertisedPropertyReadSupport() {
        assertFalse(PowerReadbackPolicy.shouldAutomaticallyRead(device(
            "mono1", setOf("set_power"))))
        assertTrue(PowerReadbackPolicy.shouldAutomaticallyRead(device(
            "mono1", setOf("get_prop", "set_power"))))
    }
}
