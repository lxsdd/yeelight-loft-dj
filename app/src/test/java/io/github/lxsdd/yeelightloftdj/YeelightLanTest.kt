package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class YeelightLanTest {
    private val response = """
        HTTP/1.1 200 OK
        Location: yeelight://192.168.178.45:55443
        id: 0x0000000012345678
        model: color4
        support: set_power set_rgb start_cf stop_cf set_music
    """.trimIndent()

    @Test fun discoveryParsesColor4() {
        val device = YeelightLan.parseAnnouncement(response)
        assertNotNull(device)
        assertEquals("192.168.178.45", device!!.ip)
        assertEquals(55443, device.port)
        assertEquals("color4", device.model)
        assertTrue(device.canFlow)
    }

    @Test fun onlyPrivateIps() {
        assertTrue(YeelightLan.isLocalIp("192.168.178.50"))
        assertTrue(YeelightLan.isLocalIp("10.0.0.1"))
        assertFalse(YeelightLan.isLocalIp("8.8.8.8"))
        assertFalse(YeelightLan.isLocalIp("not-an-ip"))
    }

    @Test fun rejectsAdvertisedInternetEndpoint() {
        assertNull(YeelightLan.parseAnnouncement(response.replace("192.168.178.45", "8.8.8.8")))
    }

    @Test fun respectsCapabilities() {
        assertFalse(YeelightLan.parseAnnouncement(response.replace("start_cf ", ""))!!.canFlow)
    }

    @Test fun mergeRetainsBulbsAcrossScans() {
        val a = YeelightDevice("id-a", "192.168.178.41", model = "color4")
        val b = YeelightDevice("id-b", "192.168.178.42", model = "color4")
        val first = YeelightLan.mergeDevices(emptyList(), listOf(a, b))
        val second = YeelightLan.mergeDevices(first, listOf(a))
        assertEquals(2, second.size)
        assertTrue(second.any { it.id == "id-b" })
    }

    @Test fun deepProbeKeepsDiscoveredDeviceId() {
        val discovered = YeelightDevice("0x0023", "192.168.178.45", model = "color4")
        val probed = YeelightDevice("ip-192.168.178.45", "192.168.178.45", model = "Yeelight (LAN)")
        val merged = YeelightLan.mergeDevices(listOf(discovered), listOf(probed))
        assertEquals(1, merged.size)
        assertEquals("0x0023", merged.single().id)
        assertEquals("color4", merged.single().model)
    }

    @Test fun changedIpReplacesSameDevice() {
        val first = YeelightDevice("id-a", "192.168.178.41")
        val next = YeelightDevice("id-a", "192.168.178.99")
        val result = YeelightLan.mergeDevices(listOf(first), listOf(next))
        assertEquals(1, result.size)
        assertEquals("192.168.178.99", result.single().ip)
    }

    @Test fun subnetScanLimitedToWifi24() {
        val ips = YeelightLan.subnetCandidates("192.168.178.50")
        assertEquals(253, ips.size)
        assertTrue("192.168.178.45" in ips)
        assertFalse("192.168.178.50" in ips)
        assertFalse("192.168.179.1" in ips)
        assertEquals(emptyList<String>(), YeelightLan.subnetCandidates("8.8.8.8"))
    }
}
