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
}
