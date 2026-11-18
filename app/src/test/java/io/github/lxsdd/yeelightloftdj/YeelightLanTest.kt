package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class YeelightLanTest {
    private val response = """
        HTTP/1.1 200 OK
        Location: yeelight://192.168.178.45:55443
        id: 0x0000000012345678
        model: color4
        support: set_power set_rgb start_cf stop_cf set_music
    """.trimIndent()

    @Test fun firmwareRejectionIdentifiesMusicStartVersusStopAndCode() {
        val error = JSONObject("""{"code":-5001,"message":"invalid params"}""")
        assertEquals(
            "set_music(1) abgelehnt (Fehlercode -5001): invalid params",
            YeelightLan.rejectionMessage("set_music",
                listOf(1, "192.168.1.20", 43000), error))
        assertEquals(
            "set_music(0) abgelehnt (Fehlercode -5001): invalid params",
            YeelightLan.rejectionMessage("set_music", listOf(0), error))
    }

    @Test fun musicPreflightNeverAssumesUnsupportedPropertyMeansOff() {
        assertEquals(false, YeelightLan.musicStateFromValue("0"))
        assertEquals(true, YeelightLan.musicStateFromValue("1"))
        assertNull(YeelightLan.musicStateFromValue(""))
        assertNull(YeelightLan.musicStateFromValue(null))
        assertNull(YeelightLan.musicPreflightRefusal(false))
        assertNotNull(YeelightLan.musicPreflightRefusal(true))
        assertNotNull(YeelightLan.musicPreflightRefusal(null))
    }

    @Test fun recoveryParserKeepsMusicEvidenceIndependentOfPower() {
        val readings = listOf("0", "off", "50", "1", "16711680", "2700",
            "0", "100", "0")
        val observation = YeelightLan.parseRecoveryProperties(readings)
        assertEquals(false, observation.flowing)
        assertEquals(false, observation.powerOn)
        assertEquals(false, observation.musicOn)
        assertNotNull(observation.snapshot)
        assertNull(YeelightLan.parseRecoveryProperties(readings.dropLast(1)).musicOn)
        assertEquals(true, YeelightLan.parseRecoveryProperties(
            readings.dropLast(1) + "1").musicOn)
    }

    @Test fun discoveryParsesColor4() {
        val device = YeelightLan.parseAnnouncement(response)
        assertNotNull(device)
        assertEquals("192.168.178.45", device!!.ip)
        assertEquals(55443, device.port)
        assertEquals("color4", device.model)
        assertTrue(device.canFlow)
    }

    @Test fun rgbChaseRejectsFlowWithoutRgbSupport() {
        val whiteFlow = YeelightDevice("white", "192.168.1.99", model = "mono",
            methods = setOf("start_cf", "stop_cf", "set_bright"))
        assertFalse(whiteFlow.canFlow)
        assertFalse(YeelightDevice("mono", "192.168.1.98", model = "mono").canFlow)
        assertTrue(YeelightDevice("color4", "192.168.1.97", model = "color4",
            methods = setOf("set_rgb", "start_cf", "stop_cf")).canFlow)
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
    @Test fun discoveryReadsLocalNameButNotCloudAlias() {
        val device = YeelightLan.parseAnnouncement(response + "\nname: Billard links\n")
        assertNotNull(device)
        assertEquals("Billard links", device!!.lanName)
        assertEquals("color4", device.model)
    }

    @Test fun deepScanPreservesKnownModelAndLocalName() {
        val ssdp = YeelightDevice("0x33", "192.168.178.60", model = "stripe",
            methods = setOf("start_cf", "set_rgb", "get_prop"), lanName = "Lichtstreifen")
        val tcp = YeelightDevice("ip-192.168.178.60", "192.168.178.60",
            model = "unknown", lanName = "")
        val merged = YeelightLan.mergeDevices(listOf(ssdp), listOf(tcp))
        assertEquals(1, merged.size)
        assertEquals("0x33", merged.single().id)
        assertEquals("stripe", merged.single().model)
        assertEquals("Lichtstreifen", merged.single().lanName)
        assertTrue(merged.single().canFlow)
    }

    @Test fun multicastUpgradesPreviouslyUnknownTcpDevice() {
        val tcp = YeelightDevice("ip-192.168.178.61", "192.168.178.61", model = "unknown",
            lanName = "Lampe lokal")
        val udp = YeelightDevice("0x34", "192.168.178.61", model = "color4",
            methods = setOf("start_cf", "set_rgb"))
        val merged = YeelightLan.mergeDevices(listOf(tcp), listOf(udp))
        assertEquals(1, merged.size)
        assertEquals("0x34", merged.single().id)
        assertEquals("color4", merged.single().model)
        assertEquals("Lampe lokal", merged.single().lanName)
    }

    @Test fun fallbackIsNotAProductModel() {
        assertFalse(YeelightLan.isSpecificModel("Yeelight (LAN)"))
        assertFalse(YeelightLan.isSpecificModel("unknown"))
        assertTrue(YeelightLan.isSpecificModel("color4"))
        assertTrue(YeelightLan.isSpecificModel("stripe"))
    }
    @Test fun atomicSceneIsPreferredWhenSupportedOrCapabilitiesUnknown() {
        assertTrue(YeelightLan.preferAtomicFlowStart(emptySet()))
        assertTrue(YeelightLan.preferAtomicFlowStart(setOf("start_cf", "set_scene")))
        assertFalse(YeelightLan.preferAtomicFlowStart(setOf("start_cf", "stop_cf")))
    }

    @Test fun successfulCommandWithUnknownFlowingIsUnverifiedNotDefiniteFailure() {
        assertEquals(FlowStartState.VERIFIED, YeelightLan.classifyFlowStart(true))
        assertEquals(FlowStartState.UNVERIFIED, YeelightLan.classifyFlowStart(null))
    }

    @Test fun flowingPropertyNeedsAnExplicitDeviceState() {
        assertEquals(true, YeelightLan.flowStateFromValue("1"))
        assertEquals(false, YeelightLan.flowStateFromValue("0"))
        assertNull(YeelightLan.flowStateFromValue(""))
        assertNull(YeelightLan.flowStateFromValue("unsupported"))
        assertNull(YeelightLan.flowStateFromValue(null))
    }

    @Test fun unsolicitedPropertiesAreNotTreatedAsCommandResponse() {
        val notification = JSONObject("""{"method":"props","params":{"power":"on"}}""")
        val staleReply = JSONObject("""{"id":41,"result":["ok"]}""")
        val expectedReply = JSONObject("""{"id":42,"result":["ok"]}""")
        assertFalse(YeelightLan.matchesReplyId(notification, 42))
        assertFalse(YeelightLan.matchesReplyId(staleReply, 42))
        assertTrue(YeelightLan.matchesReplyId(expectedReply, 42))
    }

    @Test fun directTcpProbeUsesOneSocketAndHonorsUnsolicitedProps() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger(0)
            val worker = thread {
                server.accept().use { socket ->
                    accepted.incrementAndGet()
                    val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                    val id = request.getInt("id")
                    socket.getOutputStream().write((
                        "{\"method\":\"props\",\"params\":{\"power\":\"on\"}}\r\n" +
                        "{\"id\":$id,\"result\":[\"on\",\"Couchtisch\"]}\r\n"
                    ).toByteArray())
                    socket.getOutputStream().flush()
                }
            }
            try {
                val gate = DeviceCommandGate({ 10_000L }, maxCommandsPerMinute = 4)
                val found = YeelightLan.probeEndpoint("127.0.0.1", server.localPort, gate)
                assertNotNull(found)
                assertEquals("Couchtisch", found!!.lanName)
                assertEquals(1, accepted.get())
                assertEquals(1, gate.consumed("127.0.0.1"))
            } finally {
                worker.join(2000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun directTcpProbeDoesNotRetryAClosedOrAmbiguousSocket() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger(0)
            val worker = thread {
                server.accept().use { socket ->
                    accepted.incrementAndGet()
                    socket.getInputStream().bufferedReader().readLine()
                    // The lamp could have handled the request but lost its ACK.
                    // No second socket or automatic retry is permitted.
                }
            }
            try {
                val gate = DeviceCommandGate({ 20_000L }, maxCommandsPerMinute = 4)
                assertNull(YeelightLan.probeEndpoint("127.0.0.1", server.localPort, gate))
                assertEquals(1, accepted.get())
                assertEquals(1, gate.consumed("127.0.0.1"))
            } finally {
                worker.join(2000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun directTcpProbeRetriesOnlyAnExplicitNonQuotaFirmwareRejection() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger(0)
            val worker = thread {
                repeat(2) { attempt ->
                    server.accept().use { socket ->
                        accepted.incrementAndGet()
                        val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                        val id = request.getInt("id")
                        val params = request.getJSONArray("params")
                        if (attempt == 0) {
                            assertEquals(2, params.length())
                            socket.getOutputStream().write(
                                "{\"id\":$id,\"error\":{\"message\":\"unsupported name property\"}}\r\n"
                                    .toByteArray()
                            )
                        } else {
                            assertEquals(1, params.length())
                            socket.getOutputStream().write(
                                "{\"id\":$id,\"result\":[\"off\"]}\r\n".toByteArray()
                            )
                        }
                        socket.getOutputStream().flush()
                    }
                }
            }
            try {
                val gate = DeviceCommandGate({ 30_000L }, maxCommandsPerMinute = 4)
                assertNotNull(YeelightLan.probeEndpoint("127.0.0.1", server.localPort, gate))
                assertEquals(2, accepted.get())
                assertEquals(2, gate.consumed("127.0.0.1"))
            } finally {
                worker.join(2000)
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun legacyProbeFallbackRejectsUnrelatedErrors() {
        assertTrue(YeelightLan.isUnsupportedProbeProperty("get_prop abgelehnt: unsupported name property"))
        assertTrue(YeelightLan.isUnsupportedProbeProperty("invalid params"))
        assertFalse(YeelightLan.isUnsupportedProbeProperty("device busy"))
        assertFalse(YeelightLan.isUnsupportedProbeProperty("client quota exceeded"))
        assertFalse(YeelightLan.isUnsupportedProbeProperty(null))
    }

    @Test fun quotaRejectedProbeNeverRetriesAndBlocksFurtherProbeCommands() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger(0)
            val worker = thread {
                server.accept().use { socket ->
                    accepted.incrementAndGet()
                    val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                    val id = request.getInt("id")
                    socket.getOutputStream().write(
                        "{\"id\":$id,\"error\":{\"message\":\"client quota exceeded\"}}\r\n"
                            .toByteArray()
                    )
                    socket.getOutputStream().flush()
                }
            }
            try {
                val gate = DeviceCommandGate({ 40_000L }, maxCommandsPerMinute = 4)
                assertNull(YeelightLan.probeEndpoint("127.0.0.1", server.localPort, gate))
                assertNull(YeelightLan.probeEndpoint("127.0.0.1", server.localPort, gate))
                assertEquals(1, accepted.get())
                assertEquals(1, gate.consumed("127.0.0.1"))
                assertTrue(runCatching { gate.run("127.0.0.1") {} }.exceptionOrNull()
                    is DeviceCommandGate.Deferred)
            } finally {
                worker.join(2000)
                assertFalse(worker.isAlive)
            }
        }
    }

}
