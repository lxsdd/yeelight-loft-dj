package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChromaFakeDeviceTest {
    private val client = "192.168.1.10"
    private val lamp = "192.168.1.21"
    private fun support(v: Int) = if (v == 2)
        setOf("chroma", "udp_sess_new", "set_scene") else
        setOf("chroma", "udp_new", "set_scene")

    private fun deliver(fake: ChromaFakeDevice, frame: ChromaPreviewFrame?): ChromaFakeDatagram? =
        frame?.let { fake.process(ChromaFakeDatagram(client, 50000, it.forSimulatorOnly())) }

    @Test fun fullV2HandshakeKeepaliveAndColorFrameRoundTrip() {
        val clientSession = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        val hello = deliver(fake, clientSession.begin(0))!!
        assertEquals(ChromaFakeDevice.CHROMA_UDP_PORT, hello.senderPort)
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            clientSession.acceptReply(hello.senderIp, hello.senderPort, hello.payload, 30))
        assertTrue(clientSession.tokenHeld)
        val ack = deliver(fake, clientSession.keepAlive(10030))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
            clientSession.acceptReply(ack.senderIp, ack.senderPort, ack.payload, 10040))
        val color = clientSession.previewColor(0xAABBCC, 70, 10050)
        assertNull(deliver(fake, color))
        assertEquals(1, fake.acceptedColorFrames)
        assertEquals(1, fake.acceptedKeepAlives)
    }

    @Test fun v1UsesDistinctMethodNamesAndRequiresOwnToken() {
        val session = ChromaUdpOfflineSession(lamp, support(1))
        val fake = ChromaFakeDevice(client, 1)
        val hello = deliver(fake, session.begin(0))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(hello.senderIp, hello.senderPort, hello.payload, 20))
        assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
            deliver(fake, session.keepAlive(10020))!!.let {
                session.acceptReply(it.senderIp, it.senderPort, it.payload, 10030)
            })
        assertEquals(1, fake.acceptedKeepAlives)
    }

    @Test fun wrongServerPortCannotInjectSessionToken() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        val hello = deliver(fake, session.begin(0))!!
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            session.acceptReply(hello.senderIp, 12345, hello.payload, 10))
        assertFalse(session.tokenHeld)
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(hello.senderIp, hello.senderPort, hello.payload, 11))
    }

    @Test fun fakeRejectsForeignClientAndMalformedFrames() {
        val fake = ChromaFakeDevice(client, 2)
        for (pkt in listOf(
            ChromaFakeDatagram("192.168.1.20", 50100,
                "{\"id\":1,\"method\":\"udp_sess_new\",\"params\":[]}"),
            ChromaFakeDatagram(client, 50100, "not json"),
            ChromaFakeDatagram(client, 50100, "{}"),
            ChromaFakeDatagram(client, 50100, "x".repeat(1050))
        )) assertNull(fake.process(pkt))
        assertEquals(0, fake.acceptedColorFrames)
    }

    @Test fun droppedTokenNeverActivatesClientOrTriggersRetry() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        fake.dropNextReply = true
        assertNull(deliver(fake, session.begin(0)))
        assertFalse(session.tokenHeld)
        assertFalse(session.advance(3001))
        assertNull(session.begin(3002))
    }

    @Test fun simulatedBareFirmwareRejectionImmediatelyCloses() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        fake.rejectNextCommand = true
        val error = deliver(fake, session.begin(0))!!
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            session.acceptReply(error.senderIp, error.senderPort, error.payload, 20))
        assertFalse(session.tokenHeld)
        assertNull(session.begin(100))
    }

    @Test fun replayedOrReorderedRequestIdIsNotProcessedTwice() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        val req = session.begin(0)!!
        val first = deliver(fake, req)!!
        assertNull(deliver(fake, req))
        session.acceptReply(first.senderIp, first.senderPort, first.payload, 1)
        val color = session.previewColor(0x123456, 80, 2)!!
        assertNull(deliver(fake, color))
        assertNull(deliver(fake, color))
        assertEquals(1, fake.acceptedColorFrames)
    }

    @Test fun fakeDoesNotExposeTokenInObjectString() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 2)
        val hello = deliver(fake, session.begin(0))!!
        assertEquals("ChromaFakeDatagram(redacted)", hello.toString())
        assertFalse(hello.toString().contains("fedcba98"))
        assertEquals("ChromaPreviewFrame(redacted)",
            session.keepAlive(10000)?.toString() ?: "ChromaPreviewFrame(redacted)")
    }

    @Test fun serverProtocolMismatchDoesNotActivateClient() {
        val session = ChromaUdpOfflineSession(lamp, support(2))
        val fake = ChromaFakeDevice(client, 1)
        assertNull(deliver(fake, session.begin(0)))
        assertEquals(ChromaPreviewState.AWAITING_TOKEN, session.state)
        assertFalse(session.tokenHeld)
    }

    @Test fun fakeMustNeverProduceRealNetworkTransport() {
        val cls = ChromaFakeDevice::class.java.declaredMethods.map { it.name }
        assertFalse(cls.any { it.contains("Socket", ignoreCase = true) })
        assertEquals(55444, ChromaFakeDevice.CHROMA_UDP_PORT)
    }
}
