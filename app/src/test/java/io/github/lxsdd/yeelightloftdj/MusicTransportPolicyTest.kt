package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MusicTransportPolicyTest {
    private fun lamp(
        id: String, model: String, methods: Set<String> = emptySet(),
    ) = YeelightDevice(id = id, ip = "192.168.0.${20 + id.length}", model = model, methods = methods)

    @Test fun color4RequiresBothMethodsReportedByFirmware() {
        assertFalse(MusicTransportPolicy.canArmForMusic(lamp("a", "color4")))
        assertFalse(MusicTransportPolicy.canArmForMusic(lamp(
            "a", "color4", setOf("set_rgb"))))
        assertFalse(MusicTransportPolicy.canArmForMusic(lamp(
            "a", "color4", setOf("set_music"))))
        assertTrue(MusicTransportPolicy.canArmForMusic(lamp(
            "a", "color4", setOf("set_music", "set_rgb"))))
    }

    @Test fun documentedProductModelPrefixIsRecognized() {
        assertFalse(MusicTransportPolicy.canArmForMusic(lamp("a", "yeelink.light.color4")))
        assertTrue(MusicTransportPolicy.canArmForMusic(lamp(
            "a", "COLOR4", setOf("set_music", "set_rgb"))))
    }

    @Test fun quarantinedDeviceIsExcludedEvenIfMusicIsSupported() {
        val wellness = YeelightDevice("wellness", "192.168.0.135", model="color4",
            methods=setOf("set_music", "set_rgb"))
        val couch = YeelightDevice("couch", "192.168.0.134", model="color4",
            methods=setOf("set_music", "set_rgb"))
        val unsupported = YeelightDevice("unknown", "192.168.0.136", model="color4")
        val targets = MusicTransportPolicy.eligible(
            listOf(wellness, couch, unsupported, couch.copy(id="duplicate")),
            setOf(wellness.ip)
        )
        assertEquals(listOf(couch), targets)
    }

    @Test fun unchangedLegacyBehaviorForOtherModelsWithConservativeRateLimit() {
        val desk = YeelightDevice("desk", "192.168.0.139", model="lamp")
        assertTrue(MusicTransportPolicy.canArmForMusic(desk))
    }
}
