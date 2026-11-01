package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class DirectedDiscoveryPlanTest {
    @Test fun sixMulticastAndThirteenTcpProduceExactlySevenDirectedCandidates() {
        val udp = (1..6).map {
            YeelightDevice("udp-$it", "192.168.0.$it", model = "color4")
        }
        val tcp = (1..13).map {
            YeelightDevice("tcp-$it", "192.168.0.$it")
        }
        val candidates = DirectedDiscoveryPlan.candidates(tcp, udp)
        assertEquals(7, candidates.size)
        assertEquals(7, DirectedDiscoveryPlan.missingMulticastCount(tcp, udp))
        assertTrue(candidates.none { it.ip in udp.map { u -> u.ip } })
    }

    @Test fun unicastSearchResponseUpgradesSameTcpBulbWithoutNewPhysicalDevice() {
        val tcp = YeelightDevice("ip-192.168.0.134", "192.168.0.134")
        val udp = YeelightLan.parseAnnouncement(
            "HTTP/1.1 200 OK\r\nLocation: yeelight://192.168.0.134:55443\r\n" +
            "id: 0x1234\r\nmodel: color4\r\n" +
            "support: get_prop set_power set_rgb start_cf stop_cf set_scene\r\n"
        )!!
        val merged = YeelightLan.mergeDevices(listOf(tcp), listOf(udp))
        assertEquals(1, merged.size)
        assertEquals("0x1234", merged.single().id)
        assertEquals("color4", merged.single().model)
        assertTrue("stop_cf" in merged.single().methods)
    }

    @Test fun boundedFallbackDeduplicatesRepeatedBulbs() {
        val duplicate = YeelightDevice("same", "192.168.0.22")
        val candidates = DirectedDiscoveryPlan.candidates(
            List(100) { duplicate.copy(id = "id-$it") }, emptyList())
        assertEquals(1, candidates.size)
    }
}
