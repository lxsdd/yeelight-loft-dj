package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Semantic, synthetic conformance fixtures from the publicly published
 * Yeelight Chroma Connector CUdpLight.cpp, commit
 * 769a616b9012877baf7fa3df426b38ca6697a167.
 * The OEM sample is NOT a Color4 hardware certification or an authenticated
 * network protocol; our stricter sender/ID/token gates are intentional.
 */
class ChromaOemWireContractTest {
    private val client = "192.168.1.10"
    private val lamp = "192.168.1.21"
    private val token = "fedcba9876543210fedcba9876543210"

    @Test fun standardDeviceV1AndV2MatchPinnedOemSemanticWireShapes() {
        for (version in listOf(1, 2)) {
            val prefix = if (version == 2) "udp_sess_" else "udp_"
            val support = setOf("chroma", prefix + "new", "set_scene")
            val session = ChromaUdpOfflineSession(lamp, support)
            val fake = ChromaFakeDevice(client, version, lamp)
            val helloFrame = session.begin(0)!!
            val hello = JSONObject(helloFrame.forSimulatorOnly())
            assertEquals(1, hello.getInt("id"))
            assertEquals(prefix + "new", hello.getString("method"))
            assertEquals(0, hello.getJSONArray("params").length())
            assertFalse(hello.has("token"))
            assertTrue(helloFrame.forSimulatorOnly().endsWith("\r\n"))
            val reply = fake.process(ChromaFakeDatagram(
                client, 51000, helloFrame.forSimulatorOnly()))!!
            assertEquals(prefix + "token", JSONObject(reply.payload).getString("method"))
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                session.acceptReply(reply.senderIp, reply.senderPort, reply.payload, 20))

            val keepFrame = session.keepAlive(10_020)!!
            val keep = JSONObject(keepFrame.forSimulatorOnly())
            assertEquals(2, keep.getInt("id"))
            assertEquals(prefix + "keep_alive", keep.getString("method"))
            assertEquals("keeplive_interval", keep.getJSONArray("params").getString(0))
            assertEquals("10", keep.getJSONArray("params").getString(1))
            assertEquals(token, keep.getString("token"))
            val ack = fake.process(ChromaFakeDatagram(
                client, 51000, keepFrame.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
                session.acceptReply(ack.senderIp, ack.senderPort, ack.payload, 10_030))

            val colorFrame = session.previewColor(0x123456, 80, 10_040)!!
            val color = JSONObject(colorFrame.forSimulatorOnly())
            assertEquals(3, color.getInt("id"))
            assertEquals("set_scene", color.getString("method"))
            assertEquals("color", color.getJSONArray("params").getString(0))
            assertEquals(0x123456, color.getJSONArray("params").getInt(1))
            assertEquals(80, color.getJSONArray("params").getInt(2))
            assertEquals(0, color.getJSONArray("params").getInt(3))
            assertEquals("sudden", color.getJSONArray("params").getString(4))
            assertEquals(token, color.getString("token"))
            assertNull(fake.process(ChromaFakeDatagram(
                client, 51000, colorFrame.forSimulatorOnly())))
            assertEquals(1, fake.acceptedColorFrames)
            assertFalse(colorFrame.toString().contains(token))
        }
    }

    @Test fun backgroundOnlyOemMethodsAreNotMisclassifiedAsStandardRgb() {
        // The OEM sample has separate bg_set_scene/bg_set_rgb branches.
        // We deliberately do not issue either branch without separate gates.
        for (method in listOf("bg_set_scene", "bg_set_rgb")) {
            val support = setOf("chroma", "udp_sess_new", method)
            val report = ChromaColorReadinessReport.fromCachedSupport(support)
            assertEquals(ChromaColorEvidence.RGB_SCENE_NOT_ADVERTISED, report.evidence)
            assertFalse(report.maySendUdp)
            val session = ChromaUdpOfflineSession(lamp, support)
            val fake = ChromaFakeDevice(client, 2, lamp)
            val hello = session.begin(0)!!
            val reply = fake.process(ChromaFakeDatagram(
                client, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                session.acceptReply(lamp, 55444, reply.payload, 20))
            assertNull(session.previewColor(0x123456, 70, 30))
            assertEquals(0, fake.acceptedColorFrames)
        }
    }

    @Test fun stricterThanOemReplyMatchingPreventsReplayAndTokenSubstitution() {
        val session = ChromaUdpOfflineSession(lamp,
            setOf("chroma", "udp_new", "set_scene"))
        val fake = ChromaFakeDevice(client, 1, lamp)
        val hello = session.begin(0)!!
        val reply = fake.process(ChromaFakeDatagram(
            client, 51000, hello.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(lamp, 55444, reply.payload, 20))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            session.acceptReply(lamp, 55444, reply.payload, 21))
        val keep = session.keepAlive(10_020)!!
        val forged = JSONObject().put("id", 2).put("method", "udp_token")
            .put("params", JSONObject().put("token", "0".repeat(32)))
        assertEquals(ChromaPreviewEvent.IGNORED_INVALID_REPLY,
            session.acceptReply(lamp, 55444, forged.toString(), 10_021))
        val valid = fake.process(ChromaFakeDatagram(
            client, 51000, keep.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
            session.acceptReply(lamp, 55444, valid.payload, 10_022))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            session.acceptReply(lamp, 55444, valid.payload, 10_023))
    }
}
