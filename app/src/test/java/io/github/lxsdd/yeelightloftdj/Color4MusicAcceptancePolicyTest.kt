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
        assertFalse(Color4MusicAcceptancePolicy.LIVE_TEST_APPROVED)
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp()), emptySet(), false)!!.contains("pausiert"))
        // Test internal validation against a hypothetical, explicit future
        // permission. This does NOT enable any production Color4 write.
        assertNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp()), emptySet(), false, signedTestApproved = true))
        assertNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp().copy(id = "Flur")), emptySet(), false, signedTestApproved = true))
        assertEquals(15_000L, Color4MusicAcceptancePolicy.DURATION_MS)
    }

    @Test fun safetyPauseDoesNotEraseSpecificRecoveryAndQuotaReasons() {
        val blocked = lamp(ip = "192.168.0.136")
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(blocked), setOf(blocked.ip), false)!!.contains("Firmware"))
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(blocked), emptySet(), true)!!.contains("Wiederherstellung"))
        assertTrue(Color4MusicAcceptancePolicy.refusal(
            listOf(blocked), emptySet(), false)!!.contains("pausiert"))
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
            emptySet(), false, signedTestApproved = true))
        assertNotNull(Color4MusicAcceptancePolicy.refusal(
            listOf(lamp(), lamp().copy(id = "old-cache-id")),
            emptySet(), false))
    }
}
