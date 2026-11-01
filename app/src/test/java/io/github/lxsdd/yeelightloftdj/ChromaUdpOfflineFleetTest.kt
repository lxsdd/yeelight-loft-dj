package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChromaUdpOfflineFleetTest {
    private val client = "192.168.1.10"
    private val aIp = "192.168.1.21"
    private val bIp = "192.168.1.22"
    private val v2 = setOf("chroma", "udp_sess_new", "set_scene")
    private val v1 = setOf("chroma", "udp_new", "set_scene")

    private fun send(fake: ChromaFakeDevice, frame: ChromaPreviewFrame?) =
        frame?.let { fake.process(ChromaFakeDatagram(client, 51000, it.forSimulatorOnly())) }

    private fun activate(fleet: ChromaUdpOfflineFleet, key: String, fake: ChromaFakeDevice) {
        val response = send(fake, fleet.begin(key, 0))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            fleet.acceptReply(key, response.senderIp, response.senderPort, response.payload, 20))
    }

    @Test fun twoIndependentLampsWithDistinctV1V2SessionsAndBudgets() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(bIp, v1))))
        val a = ChromaFakeDevice(client, 2, aIp)
        val b = ChromaFakeDevice(client, 1, bIp)
        activate(fleet, "a", a)
        activate(fleet, "b", b)
        assertNull(send(a, fleet.color("a", 0x123456, 70, 30)))
        assertNull(send(b, fleet.color("b", 0xABCDEF, 80, 40)))
        assertEquals(1, a.acceptedColorFrames)
        assertEquals(1, b.acceptedColorFrames)
        assertEquals(1, fleet.snapshot("a")!!.framesAttempted)
        assertEquals(1, fleet.snapshot("b")!!.framesAttempted)
        assertTrue(fleet.snapshot("a")!!.simulatedTokenHeld)
        assertTrue(fleet.snapshot("b")!!.simulatedTokenHeld)
    }

    @Test fun noCapabilityOrOnlySubstringCannotBegin() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp,
                setOf("not_chroma", "udp_sess_new", "set_scene"))))
        assertNull(fleet.begin("a", 0))
        assertNull(fleet.color("a", 100, 50, 1))
        assertEquals(0, fleet.snapshot("a")!!.framesAttempted)
    }

    @Test fun fiveThousandRapidUiEventsCannotFloodOrCreateBacklog() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        val a = ChromaFakeDevice(client, 2, aIp)
        activate(fleet, "a", a)
        for (i in 0 until 5000) send(a, fleet.color("a", i, 60, 30L + i))
        assertEquals(45, fleet.snapshot("a")!!.framesAttempted)
        assertEquals(45, a.acceptedColorFrames)
        assertTrue(fleet.snapshot("a")!!.safetyStopped)
        assertNull(fleet.keepAlive("a", 10_000))
        assertNull(fleet.begin("a", 10_001))
    }

    @Test fun oldFramesDroppedAndOnlyLatestSampleEligibleAtNextWindow() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        activate(fleet, "a", ChromaFakeDevice(client, 2, aIp))
        assertNotNull(fleet.color("a", 1, 60, 30))
        assertNull(fleet.color("a", 2, 60, 31))
        assertNull(fleet.color("a", 3, 60, 90))
        val frame = JSONObject(fleet.color("a", 0xAABBCC, 60, 130)!!.forSimulatorOnly())
        assertEquals(0xAABBCC, frame.getJSONArray("params").getInt(1))
        assertEquals(2, fleet.snapshot("a")!!.framesAttempted)
    }

    @Test fun firmwareQuotaQuarantineIsPermanentWithinSimulation() {
        val blocked = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2), true)))
        assertNull(blocked.begin("a", 0))
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        activate(fleet, "a", ChromaFakeDevice(client, 2, aIp))
        assertTrue(fleet.quarantine("a"))
        assertFalse(fleet.snapshot("a")!!.simulatedTokenHeld)
        assertNull(fleet.color("a", 22, 60, 40))
        assertNull(fleet.begin("a", 100_000))
    }

    @Test fun deviceErrorClosesOnlyAffectedLaneWithoutRetry() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(bIp, v2))))
        val a = ChromaFakeDevice(client, 2, aIp)
        val b = ChromaFakeDevice(client, 2, bIp)
        activate(fleet, "a", a)
        activate(fleet, "b", b)
        a.rejectNextCommand = true
        val error = send(a, fleet.keepAlive("a", 10_020))!!
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            fleet.acceptReply("a", error.senderIp, error.senderPort, error.payload, 10_030))
        assertNull(fleet.color("a", 1, 60, 10_040))
        assertNotNull(fleet.color("b", 2, 60, 10_040))
        assertTrue(fleet.snapshot("a")!!.safetyStopped)
        assertFalse(fleet.snapshot("b")!!.safetyStopped)
    }

    @Test fun droppedHandshakeFailsClosedWithoutReconnect() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        val fake = ChromaFakeDevice(client, 2, aIp)
        fake.dropNextReply = true
        assertNull(send(fake, fleet.begin("a", 0)))
        assertNull(fleet.color("a", 20, 50, 100))
        assertNull(fleet.begin("a", 101))
        assertNull(fleet.keepAlive("a", 3100))
        assertTrue(fleet.snapshot("a")!!.safetyStopped)
        assertEquals(0, fake.acceptedColorFrames)
    }

    @Test fun foreignSenderCannotActivateSecondLane() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(bIp, v2))))
        val response = send(ChromaFakeDevice(client, 2, aIp), fleet.begin("a", 0))!!
        assertNotNull(fleet.begin("b", 0))
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            fleet.acceptReply("b", response.senderIp, response.senderPort, response.payload, 10))
        assertFalse(fleet.snapshot("b")!!.simulatedTokenHeld)
    }

    @Test fun lifetimeAndClockRegressionStopOnlyOwnLane() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(bIp, v2))))
        activate(fleet, "a", ChromaFakeDevice(client, 2, aIp))
        activate(fleet, "b", ChromaFakeDevice(client, 2, bIp))
        assertNotNull(fleet.color("a", 10, 60, 100))
        assertNull(fleet.color("a", 11, 60, 90))
        assertTrue(fleet.snapshot("a")!!.safetyStopped)
        assertNotNull(fleet.color("b", 22, 60, 90))
        assertNull(fleet.color("b", 23, 60, 15_000))
        assertTrue(fleet.snapshot("b")!!.safetyStopped)
    }

    @Test fun noUnknownLaneImplicitFallbackOrCrossTalk() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        assertNull(fleet.begin("missing", 0))
        assertNull(fleet.color("missing", 1, 50, 0))
        assertNull(fleet.keepAlive("missing", 1))
        assertNull(fleet.acceptReply("missing", aIp, 55444, "{}", 2))
        assertNull(fleet.snapshot("missing"))
        assertFalse(fleet.quarantine("missing"))
        assertFalse(fleet.stop("missing"))
    }

    @Test fun invalidTopologyFailsWithoutUsingAnyTransport() {
        val session = ChromaUdpOfflineSession(aIp, v2)
        assertThrows(IllegalArgumentException::class.java) { ChromaUdpOfflineFleet(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaUdpOfflineFleet(listOf(
                ChromaOfflineLampSpec("a", session),
                ChromaOfflineLampSpec("a", session)))
        }
    }

    @Test fun snapshotHidesTokenAndDeviceAddress() {
        val fleet = ChromaUdpOfflineFleet(listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(aIp, v2))))
        activate(fleet, "a", ChromaFakeDevice(client, 2, aIp))
        val status = fleet.snapshot("a").toString()
        assertFalse(status.contains("fedcba9876543210"))
        assertFalse(status.contains(aIp))
        assertEquals(0, fleet.snapshot("a")!!.framesAttempted)
    }
}
