package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTrafficPolicyTest {
    private fun color(ip: String, id: String = ip) = YeelightDevice(
        id = id, ip = ip, model = "color4",
        methods = setOf("get_prop", "set_rgb", "set_music")
    )

    @Test fun normalSearchNeverPlansTcpProbes() {
        assertEquals(0, DiscoveryTrafficPolicy.NORMAL_SEARCH_TCP_PROBES)
    }

    @Test fun knownColor4UsesDirectedUdpOnlyIfMulticastDidNotAnswer() {
        val a = color("192.168.0.134", "Couchtisch")
        val b = color("192.168.0.135", "Wellness")
        val c = color("192.168.0.136", "Wand")
        val targets = DiscoveryTrafficPolicy.directedUdpTargets(
            known = listOf(a, b, c, a.copy(id = "duplicate")),
            multicast = listOf(a),
            quarantinedIps = setOf(b.ip),
        )
        assertEquals(listOf(c.ip), targets.map { it.ip })
    }

    @Test fun deduplicateAndBoundDirectedUdpQueries() {
        val devices = (10..90).map { color("192.168.1.$it") }
        assertEquals(24, DiscoveryTrafficPolicy.directedUdpTargets(
            devices, emptyList(), emptySet()
        ).size)
        assertEquals(2, DiscoveryTrafficPolicy.directedUdpTargets(
            devices.take(3) + devices.take(3), emptyList(), emptySet(), limit = 2
        ).size)
    }

    @Test fun invalidOrPublicTargetsAreNeverSearched() {
        val valid = color("192.168.0.19")
        val candidates = listOf(color("8.8.8.8"), color("not-an-ip"), valid)
        assertEquals(listOf(valid), DiscoveryTrafficPolicy.directedUdpTargets(
            candidates, emptyList(), emptySet()
        ))
    }

    @Test fun allPreviouslyKnownQuarantinedDevicesAreSkipped() {
        val known = listOf(color("192.168.0.134"), color("192.168.0.135"))
        assertTrue(DiscoveryTrafficPolicy.directedUdpTargets(
            known, emptyList(), known.map { it.ip }.toSet()
        ).isEmpty())
    }

    @Test fun invalidLimitIsRejectedBeforeAnyDiscovery() {
        assertTrue(runCatching {
            DiscoveryTrafficPolicy.directedUdpTargets(emptyList(), emptyList(), emptySet(), 0)
        }.isFailure)
        assertTrue(runCatching {
            DiscoveryTrafficPolicy.directedUdpTargets(emptyList(), emptyList(), emptySet(), 25)
        }.isFailure)
    }
}
