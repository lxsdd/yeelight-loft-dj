package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChromaUdpOfflineSessionTest {
    private val ip = "192.168.1.21"
    private val validToken = "0123456789abcdef0123456789abcdef"
    private val v2 = setOf("chroma", "udp_sess_new", "set_scene")
    private val v1 = setOf("chroma", "udp_new", "set_scene")

    private fun response(id: Int, method: String = "udp_sess_token",
                         token: String = validToken): String =
        JSONObject().put("id", id).put("method", method)
            .put("params", JSONObject().put("token", token)).toString()

    private fun startActive(methods: Set<String> = v2): ChromaUdpOfflineSession {
        val sim = ChromaUdpOfflineSession(ip, methods)
        val begin = JSONObject(sim.begin(0)!!.forSimulatorOnly())
        assertEquals(1, begin.getInt("id"))
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            sim.acceptReply(ip, response(1, if ("udp_sess_new" in methods)
                "udp_sess_token" else "udp_token"), 50))
        return sim
    }

    @Test fun missingSupportCannotBeginEvenIfKnownColor4ModelOutsideSimulator() {
        assertNull(ChromaUdpOfflineSession(ip, emptySet()).begin(0))
        assertNull(ChromaUdpOfflineSession(ip, setOf("set_music", "set_scene")).begin(0))
        assertNull(ChromaUdpOfflineSession(ip, setOf("chroma")).begin(0))
    }

    @Test fun v2HandshakeProducesCorrectWireMethodAndNoTokenBeforeDeviceReply() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        val frame = sim.begin(100)!!
        val json = JSONObject(frame.forSimulatorOnly())
        assertEquals("udp_sess_new", json.getString("method"))
        assertEquals(0, json.getJSONArray("params").length())
        assertFalse(json.has("token"))
        assertTrue(frame.forSimulatorOnly().endsWith("\r\n"))
        assertEquals("ChromaPreviewFrame(redacted)", frame.toString())
        assertNull(sim.begin(101))
    }

    @Test fun v1AcceptsOnlyMatchingV1ReplyAndTracksToken() {
        val sim = ChromaUdpOfflineSession(ip, v1)
        val begin = JSONObject(sim.begin(0)!!.forSimulatorOnly())
        assertEquals("udp_new", begin.getString("method"))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, response(1), 15))
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            sim.acceptReply(ip, response(1, "udp_token"), 20))
        assertTrue(sim.tokenHeld)
        assertEquals(ChromaPreviewState.ACTIVE, sim.state)
    }

    @Test fun rejectsUntrustedSenderWrongIdAndWrongMethod() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            sim.acceptReply("192.168.1.22", response(1), 10))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, response(2), 20))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, response(1, "fake"), 30))
        assertFalse(sim.tokenHeld)
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            sim.acceptReply(ip, response(1), 40))
    }

    @Test fun rejectsMalformedAndOversizedTokensAndPayloads() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        for (payload in listOf(
            "not json", "{\"id\":1,\"method\":\"udp_sess_token\"}",
            response(1, token = "x".repeat(32)),
            response(1, token = "12345678"),
            response(1) + "x".repeat(1024),
        )) {
            assertEquals(ChromaPreviewEvent.IGNORED_INVALID_REPLY,
                sim.acceptReply(ip, payload, 10))
            assertFalse(sim.tokenHeld)
        }
    }

    @Test fun timedOutHandshakeCannotConsumeLateToken() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        assertFalse(sim.advance(3001))
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, response(1), 3002))
        assertFalse(sim.tokenHeld)
    }

    @Test fun explicitErrorClosesWithoutAutomaticReplay() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        val error = JSONObject().put("id", 1).put("method", "udp_sess_token")
            .put("params", JSONObject()).put("error",
                JSONObject().put("code", -1).put("message", "client quota exceeded"))
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            sim.acceptReply(ip, error.toString(), 10))
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
        assertNull(sim.begin(11))
        assertNull(sim.keepAlive(10000))
    }

    @Test fun bareErrorWithoutParamsFailsClosedInsteadOfBeingIgnored() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        val bareError = JSONObject().put("id", 1).put("error",
            JSONObject().put("code", -1).put("message", "quota"))
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            sim.acceptReply(ip, bareError.toString(), 10))
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
        assertFalse(sim.tokenHeld)
        assertNull(sim.begin(11))
    }

    @Test fun foreignOrMismatchedErrorMustNotCloseValidSession() {
        val sim = ChromaUdpOfflineSession(ip, v2)
        sim.begin(0)
        val bareError = JSONObject().put("id", 1).put("error",
            JSONObject().put("code", -1))
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            sim.acceptReply("192.168.1.22", bareError.toString(), 1))
        val wrongId = JSONObject().put("id", 7).put("error",
            JSONObject().put("code", -1))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, wrongId.toString(), 2))
        assertEquals(ChromaPreviewState.AWAITING_TOKEN, sim.state)
    }

    @Test fun tokenizedKeepAliveRequiresMatchingIdSenderAndToken() {
        val sim = startActive()
        assertNull(sim.keepAlive(9999))
        val keepalive = JSONObject(sim.keepAlive(10050)!!.forSimulatorOnly())
        assertEquals("udp_sess_keep_alive", keepalive.getString("method"))
        assertEquals(validToken, keepalive.getString("token"))
        assertEquals("keeplive_interval", keepalive.getJSONArray("params").getString(0))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            sim.acceptReply(ip, response(99), 10051))
        assertEquals(ChromaPreviewEvent.IGNORED_INVALID_REPLY,
            sim.acceptReply(ip, response(2, token = "ffffffffffffffffffffffffffffffff"), 10052))
        assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
            sim.acceptReply(ip, response(2), 10053))
        assertEquals(0, sim.ignoredOrMissedKeepalives)
    }

    @Test fun silenceExpiryErasesTokenAndForbidsColorFrames() {
        val sim = startActive()
        assertNull(sim.previewColor(100, 50, 40_051))
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
        assertFalse(sim.tokenHeld)
        assertNull(sim.keepAlive(40_052))
    }

    @Test fun fourMissedIntervalsCloseWithoutNetworkSideEffects() {
        val sim = startActive()
        assertNotNull(sim.keepAlive(10_050))
        assertNotNull(sim.keepAlive(20_050))
        assertNotNull(sim.keepAlive(30_050))
        assertNull(sim.keepAlive(40_050))
        assertFalse(sim.tokenHeld)
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
    }

    @Test fun colorsRequireDeclaredSceneMethodAndCorrectRanges() {
        val sim = startActive()
        val frame = sim.previewColor(0xFF00CC, 85, 60)!!
        val color = JSONObject(frame.forSimulatorOnly())
        assertEquals("set_scene", color.getString("method"))
        assertEquals("color", color.getJSONArray("params").getString(0))
        assertEquals(0xFF00CC, color.getJSONArray("params").getInt(1))
        assertEquals(85, color.getJSONArray("params").getInt(2))
        assertFalse(frame.toString().contains(validToken))
        assertNull(sim.previewColor(0x1000000, 50, 70))
        assertNull(sim.previewColor(-1, 50, 70))
        assertNull(sim.previewColor(123, 0, 70))
        assertNull(sim.previewColor(123, 101, 70))
        val noScene = startActive(setOf("chroma", "udp_sess_new"))
        assertNull(noScene.previewColor(123, 50, 60))
    }

    @Test fun backwardVirtualClockClosesRatherThanReplaying() {
        val sim = startActive()
        assertNull(sim.previewColor(100, 50, 40))
        assertEquals(ChromaPreviewState.CLOSED, sim.state)
    }
}
