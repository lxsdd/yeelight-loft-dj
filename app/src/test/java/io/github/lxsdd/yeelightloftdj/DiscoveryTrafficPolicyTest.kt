package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTrafficPolicyTest {
    private fun color(ip: String, id: String = ip) = YeelightDevice(
        id = id, ip = ip, model = "color4",
        methods = setOf("get_prop", "set_rgb", "set_music")
    )

    @Test fun normalSearchOnlyCallsMulticastAndTargetedUdpWithNoTcpDependency() {
        val multicastLamp = color("192.168.0.134")
        val blockedLamp = color("192.168.0.135")
        val allowedLamp = color("192.168.0.136")
        val calls = mutableListOf<String>()
        val result = DiscoveryTrafficPolicy.normalSearch(
            known = listOf(multicastLamp, blockedLamp, allowedLamp),
            quarantinedIps = setOf(blockedLamp.ip),
            multicast = {
                calls.add("udp-multicast")
                listOf(multicastLamp)
            },
            directed = { targets ->
                calls.add("udp-directed")
                assertEquals(listOf(allowedLamp), targets)
                targets
            }
        )
        assertEquals(listOf("udp-multicast", "udp-directed"), calls)
        assertEquals(listOf(multicastLamp), result.multicast)
        assertEquals(listOf(allowedLamp), result.directed)
    }

    @Test fun noDirectedProbeWhenAllKnownLampsAreQuarantinedOrAlreadyAnnounced() {
        val healthy = color("192.168.0.134")
        val blocked = color("192.168.0.135")
        val result = DiscoveryTrafficPolicy.normalSearch(
            known = listOf(healthy, blocked),
            quarantinedIps = setOf(blocked.ip),
            multicast = { listOf(healthy) },
            directed = { error("Normal search should not ask already-seen or blocked bulb") }
        )
        assertEquals(listOf(healthy), result.multicast)
        assertTrue(result.directed.isEmpty())
    }

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
