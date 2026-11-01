package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4MusicAcceptancePolicyTest {
    private fun lamp(
        ip: String = "192.168.0.134",
        model: String = "color4",
        methods: Set<String> = setOf("set_music", "set_rgb", "get_prop", "set_power"),
    ) = YeelightDevice("Couchtisch", ip, model = model, methods = methods)

    @Test fun permitsOnlyOneExplicitlyIdentifiedMusicCapableColor4() {
        assertNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp()), emptySet(), false))
        assertEquals(15_000L, Color4MusicAcceptancePolicy.DURATION_MS)
    }

    @Test fun rejectsZeroOrMultiplePhysicalLampTargets() {
        assertNotNull(Color4MusicAcceptancePolicy.refusal(emptyList(), emptySet(), false))
        assertNotNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(), lamp(ip = "192.168.0.135")),
            emptySet(), false))
    }

    @Test fun refusesFirmwareBlockedBulbAndAllOpenRecoveries() {
        val device = lamp()
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(device), setOf(device.ip), false)!!.contains("Firmware"))
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(device), emptySet(), true)!!.contains("Wiederherstellung"))
    }

    @Test fun refusesUnverifiedColor4ModelAndMissingMusicCapability() {
        assertNotNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(model = "unknown")), emptySet(), false))
        assertNotNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(methods = setOf("set_rgb"))), emptySet(), false))
        assertNotNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(methods = setOf("set_music"))), emptySet(), false))
    }

    @Test fun deduplicatedAliasesCannotTriggerTwoCommandsToSameIp() {
        assertNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(), lamp().copy(id = "old-cache-id")),
            emptySet(), false))
    }
}
