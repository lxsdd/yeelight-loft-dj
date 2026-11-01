package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChromaOfflineLatestValueTest {
    private val appIp = "192.168.1.10"
    private val aIp = "192.168.1.21"
    private val bIp = "192.168.1.22"
    private val v2 = setOf("chroma", "udp_sess_new", "set_scene")
    private val v1 = setOf("chroma", "udp_new", "set_scene")

    private fun create(ip: String = aIp, methods: Set<String> = v2):
        Pair<ChromaUdpOfflineFleet, ChromaFakeDevice> {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("lamp", ChromaUdpOfflineSession(ip, methods))))
        val fake = ChromaFakeDevice(appIp, if ("udp_new" in methods) 1 else 2, ip)
        val hello = fleet.begin("lamp", 0)!!
        val reply = fake.process(ChromaFakeDatagram(appIp, 51000,
            hello.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            fleet.acceptReply("lamp", reply.senderIp, reply.senderPort, reply.payload, 20))
        return fleet to fake
    }

    @Test fun fiveThousandSliderSamplesCoalesceToOneLatestFrame() {
        val (fleet, fake) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        for (i in 0 until 5000) assertTrue(buffer.offer("lamp", i, 70, 30))
        assertEquals(4999L, buffer.snapshot("lamp")!!.supersededSamples)
        val frame = buffer.poll("lamp", 31)!!
        assertEquals(4999, JSONObject(frame.forSimulatorOnly())
            .getJSONArray("params").getInt(1))
        assertNull(fake.process(ChromaFakeDatagram(appIp, 51000, frame.forSimulatorOnly())))
        assertEquals(1, fake.acceptedColorFrames)
        assertEquals(1, buffer.snapshot("lamp")!!.simulatedFramesIssued)
        assertFalse(buffer.snapshot("lamp")!!.pending)
        assertNull(buffer.poll("lamp", 32))
    }

    @Test fun newestValueSurvivesOneRateWindowWithoutReplayingIntermediateFrames() {
        val (fleet, fake) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("lamp", 0x111111, 50, 30))
        val first = buffer.poll("lamp", 30)!!
        fake.process(ChromaFakeDatagram(appIp, 51000, first.forSimulatorOnly()))
        for (i in 0 until 80) assertTrue(buffer.offer("lamp", i + 1, 60, 31L + i))
        assertNull(buffer.poll("lamp", 110))
        val latest = buffer.poll("lamp", 130)!!
        assertEquals(80, JSONObject(latest.forSimulatorOnly())
            .getJSONArray("params").getInt(1))
        fake.process(ChromaFakeDatagram(appIp, 51000, latest.forSimulatorOnly()))
        assertEquals(2, fake.acceptedColorFrames)
        assertEquals(2, fleet.snapshot("lamp")!!.framesAttempted)
    }

    @Test fun pendingFrameExpiresInsteadOfRevivingOldColorAfterLongSilence() {
        val (fleet, fake) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("lamp", 0x123456, 70, 40))
        assertNull(buffer.poll("lamp", 300))
        assertEquals(1L, buffer.snapshot("lamp")!!.expiredSamples)
        assertFalse(buffer.snapshot("lamp")!!.pending)
        assertNull(buffer.poll("lamp", 301))
        assertEquals(0, fake.acceptedColorFrames)
    }

    @Test fun repeatedIdenticalColorsDoNotGenerateDuplicateFrames() {
        val (fleet, fake) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("lamp", 1234, 70, 30))
        val first = buffer.poll("lamp", 30)!!
        fake.process(ChromaFakeDatagram(appIp, 51000, first.forSimulatorOnly()))
        assertTrue(buffer.offer("lamp", 1234, 70, 140))
        assertNull(buffer.poll("lamp", 140))
        assertEquals(1, buffer.snapshot("lamp")!!.simulatedFramesIssued)
        assertTrue(buffer.offer("lamp", 1234, 71, 240))
        assertNotNull(buffer.poll("lamp", 240))
        assertEquals(2, buffer.snapshot("lamp")!!.simulatedFramesIssued)
    }

    @Test fun rejectsInvalidColorsUnknownLanesAndMissingSession() {
        val (fleet, _) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertFalse(buffer.offer("missing", 1, 70, 30))
        assertNull(buffer.snapshot("missing"))
        assertFalse(buffer.offer("lamp", -1, 70, 30))
        assertFalse(buffer.offer("lamp", 0x1000000, 70, 30))
        assertFalse(buffer.offer("lamp", 0, 0, 30))
        assertFalse(buffer.offer("lamp", 0, 101, 30))
        assertFalse(buffer.offer("lamp", 0, 50, -1))
        assertNull(buffer.poll("lamp", 30))
        assertEquals(0, fleet.snapshot("lamp")!!.framesAttempted)
    }

    @Test fun quotaAndStopErasePendingWithoutTcpFallback() {
        val (fleet, _) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("lamp", 123, 50, 30))
        assertTrue(buffer.quarantine("lamp"))
        assertTrue(buffer.snapshot("lamp")!!.terminal)
        assertFalse(buffer.snapshot("lamp")!!.pending)
        assertNull(buffer.poll("lamp", 31))
        assertFalse(buffer.offer("lamp", 456, 50, 32))
        assertEquals(0, fleet.snapshot("lamp")!!.framesAttempted)
    }

    @Test fun backwardClockFailClosesOnlyThatLane() {
        val (fleet, _) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("lamp", 123, 50, 100))
        assertNull(buffer.poll("lamp", 99))
        assertTrue(buffer.snapshot("lamp")!!.terminal)
        assertNull(buffer.poll("lamp", 110))
    }

    @Test fun stageDoesNotWorkBeforeHandshake() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("lamp", ChromaUdpOfflineSession(aIp, v2))))
        val buffer = ChromaOfflineLatestValue(fleet)
        assertFalse(buffer.offer("lamp", 100, 70, 10))
        assertNull(buffer.poll("lamp", 20))
        assertEquals(0, fleet.snapshot("lamp")!!.framesAttempted)
    }

    @Test fun supportWithoutSetSceneDoesNotProduceOutput() {
        val (fleet, fake) = create(methods = setOf("chroma", "udp_sess_new"))
        val buffer = ChromaOfflineLatestValue(fleet)
        assertFalse(buffer.offer("lamp", 100, 70, 30))
        assertNull(buffer.poll("lamp", 30))
        assertNull(buffer.poll("lamp", 130))
        assertEquals(0, fake.acceptedColorFrames)
    }

    @Test fun twoLanesAreIsolatedEvenWithDifferentProtocolVersions() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(bIp, v1))))
        val a = ChromaFakeDevice(appIp, 2, aIp)
        val b = ChromaFakeDevice(appIp, 1, bIp)
        for ((key, device) in listOf("a" to a, "b" to b)) {
            val hello = fleet.begin(key, 0)!!
            val reply = device.process(ChromaFakeDatagram(appIp, 51000,
                hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(key, reply.senderIp, reply.senderPort, reply.payload, 20))
        }
        val buffer = ChromaOfflineLatestValue(fleet)
        assertTrue(buffer.offer("a", 0xFF0000, 60, 30))
        assertTrue(buffer.offer("b", 0x0000FF, 70, 31))
        val fA = buffer.poll("a", 32)!!
        val fB = buffer.poll("b", 33)!!
        a.process(ChromaFakeDatagram(appIp, 51000, fA.forSimulatorOnly()))
        b.process(ChromaFakeDatagram(appIp, 51000, fB.forSimulatorOnly()))
        assertEquals(1, a.acceptedColorFrames)
        assertEquals(1, b.acceptedColorFrames)
        buffer.quarantine("a")
        assertFalse(buffer.offer("a", 123, 60, 40))
        assertTrue(buffer.offer("b", 0x00FF00, 70, 140))
        assertNotNull(buffer.poll("b", 140))
        assertEquals(1, buffer.snapshot("b")!!.supersededSamples + 1L)
    }

    @Test fun expiredSessionRejectsNewSamplesBeforeAFrameCanBeBuffered() {
        val (fleet, _) = create()
        val buffer = ChromaOfflineLatestValue(fleet)
        assertFalse(buffer.offer("lamp", 1234, 70, 15_000))
        assertTrue(fleet.snapshot("lamp")!!.safetyStopped)
        assertFalse(buffer.snapshot("lamp")!!.pending)
        assertNull(buffer.poll("lamp", 15_001))
    }

    @Test fun cannotSetUnsafePendingTtlOrDiscloseColorsThroughSnapshots() {
        val (fleet, _) = create()
        assertThrows(IllegalArgumentException::class.java) {
            ChromaOfflineLatestValue(fleet, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaOfflineLatestValue(fleet, 1001)
        }
        val buffer = ChromaOfflineLatestValue(fleet)
        buffer.offer("lamp", 0xAABBCC, 50, 30)
        assertFalse(buffer.snapshot("lamp").toString().contains("AABBCC"))
        assertFalse(buffer.snapshot("lamp").toString().contains(aIp))
    }
}
