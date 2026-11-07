package io.github.lxsdd.yeelightloftdj

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Strictly in-memory v1/v2 color-error correlation; no UDP/TCP or OEM bytes. */
class ChromaCorrelatedColorErrorTest {
    private val client = "192.168.1.10"
    private val ip = "192.168.1.21"

    private fun methods(v: Int) =
        setOf("chroma", if (v == 2) "udp_sess_new" else "udp_new", "set_scene")

    private data class Active(val session: ChromaUdpOfflineSession,
                              val fake: ChromaFakeDevice, val ip: String)

    private fun active(v: Int = 2, endpoint: String = ip): Active {
        val session = ChromaUdpOfflineSession(endpoint, methods(v))
        val fake = ChromaFakeDevice(client, v, endpoint)
        val hello = session.begin(0)!!
        val response = fake.process(ChromaFakeDatagram(
            client, 51000, hello.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(endpoint, 55444, response.payload, 20))
        return Active(session, fake, endpoint)
    }

    private fun id(frame: ChromaPreviewFrame): Int =
        JSONObject(frame.forSimulatorOnly()).getInt("id")

    private fun error(requestId: Any): String =
        JSONObject().put("id", requestId).put("error",
            JSONObject().put("code", -123).put("message", "synthetic rejection")).toString()

    @Test fun v1AndV2FakeDeviceColorErrorsAreTerminal() {
        for (v in listOf(1, 2)) {
            val h = active(v)
            val color = h.session.previewColor(0x112233, 60, 30)!!
            h.fake.rejectNextCommand = true
            val rejection = h.fake.process(ChromaFakeDatagram(
                client, 51000, color.forSimulatorOnly()))!!
            assertEquals(id(color), JSONObject(rejection.payload).getInt("id"))
            assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
                h.session.acceptReply(rejection.senderIp, rejection.senderPort,
                    rejection.payload, 40))
            assertFalse(h.session.tokenHeld)
            assertNull(h.session.previewColor(0x334455, 60, 41))
            assertNull(h.session.keepAlive(10_041))
            assertEquals(0, h.fake.acceptedColorFrames)
        }
    }

    @Test fun bareColorErrorNeedsNoMethodParamsOrToken() {
        val h = active()
        val frame = h.session.previewColor(0xABCDEF, 70, 30)!!
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 31))
        assertEquals(ChromaPreviewState.CLOSED, h.session.state)
    }

    @Test fun unrelatedRequestIdDoesNotQuarantine() {
        val h = active()
        val frame = h.session.previewColor(0x111111, 70, 30)!!
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444, error(id(frame) + 500), 31))
        assertTrue(h.session.tokenHeld)
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 32))
    }

    @Test fun wrongSenderAndPortCannotTriggerColorQuotaStop() {
        val h = active()
        val frame = h.session.previewColor(0x222222, 70, 30)!!
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            h.session.acceptReply("192.168.1.99", 55444, error(id(frame)), 31))
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            h.session.acceptReply(h.ip, 55443, error(id(frame)), 32))
        assertTrue(h.session.tokenHeld)
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 33))
    }

    @Test fun stringAndFractionalErrorIdsCannotImpersonateInteger() {
        val h = active()
        val frame = h.session.previewColor(0x222222, 70, 30)!!
        for (bad in listOf(id(frame).toString(), id(frame) + 0.5)) {
            assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
                h.session.acceptReply(h.ip, 55444, error(bad), 31))
        }
        assertTrue(h.session.tokenHeld)
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 32))
    }

    @Test fun oldHandshakeErrorDoesNotCloseActiveColorSession() {
        val h = active()
        val frame = h.session.previewColor(0x334455, 80, 30)!!
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444, error(1), 31))
        assertTrue(h.session.tokenHeld)
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 32))
    }

    @Test fun optionalColorSuccessCannotRefreshTokenOrEraseDelayedError() {
        val h = active()
        val frame = h.session.previewColor(0x556677, 80, 30)!!
        val ack = JSONObject().put("id", id(frame))
            .put("result", JSONArray().put("ok")).toString()
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444, ack, 31))
        assertTrue(h.session.tokenHeld)
        val keep = h.session.keepAlive(10_020)!!
        assertEquals(3, id(keep))
        val keepReply = h.fake.process(ChromaFakeDatagram(
            client, 51000, keep.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
            h.session.acceptReply(h.ip, 55444, keepReply.payload, 10_030))
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 10_031))
    }

    @Test fun colorErrorWhileKeepalivePendingIsTerminalWithoutReplay() {
        val h = active()
        val frame = h.session.previewColor(0x778899, 50, 30)!!
        val keep = h.session.keepAlive(10_020)!!
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 10_021))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444,
                JSONObject().put("id", id(keep)).put("method", "udp_sess_token")
                    .put("params", JSONObject().put("token",
                        "fedcba9876543210fedcba9876543210")).toString(), 10_022))
        assertNull(h.session.keepAlive(20_020))
    }

    @Test fun unadvertisedSceneCannotRegisterAnErrorId() {
        val session = ChromaUdpOfflineSession(ip, setOf("chroma", "udp_sess_new"))
        val fake = ChromaFakeDevice(client, 2, ip)
        val hello = session.begin(0)!!
        val reply = fake.process(ChromaFakeDatagram(
            client, 51000, hello.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(ip, 55444, reply.payload, 20))
        assertNull(session.previewColor(0x112233, 50, 30))
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            session.acceptReply(ip, 55444, error(2), 31))
        assertTrue(session.tokenHeld)
    }

    @Test fun directSessionClosesOnFortySixthUnackedColorWithoutEviction() {
        val h = active()
        val issued = (0 until 45).map { n ->
            h.session.previewColor(n, 50, 30L + n)!!
        }
        assertEquals(45, issued.map { id(it) }.toSet().size)
        assertTrue(h.session.tokenHeld)
        assertNull(h.session.previewColor(46, 50, 75))
        assertEquals(ChromaPreviewState.CLOSED, h.session.state)
        assertFalse(h.session.tokenHeld)
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444, error(id(issued.first())), 76))
    }

    @Test fun explicitStopErasesOutstandingIdsAndCannotRearm() {
        val h = active()
        val frame = h.session.previewColor(0x010203, 50, 30)!!
        h.session.close()
        assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
            h.session.acceptReply(h.ip, 55444, error(id(frame)), 31))
        assertNull(h.session.begin(32))
        assertNull(h.session.previewColor(0x010204, 50, 33))
    }

    @Test fun fleetErrorQuarantinesOnlyMatchingLaneAndPreservesOtherBudget() {
        val specs = (0..1).map { i ->
            ChromaOfflineLampSpec("lamp-" + i, ChromaUdpOfflineSession(
                "192.168.1." + (21 + i), methods(if (i == 0) 2 else 1)))
        }
        val fleet = ChromaUdpOfflineFleet(specs)
        specs.forEachIndexed { i, spec ->
            val endpoint = "192.168.1." + (21 + i)
            val fake = ChromaFakeDevice(client, if (i == 0) 2 else 1, endpoint)
            val hello = fleet.begin(spec.key, 0)!!
            val reply = fake.process(ChromaFakeDatagram(
                client, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(spec.key, endpoint, 55444, reply.payload, 20))
        }
        val a = fleet.color("lamp-0", 0x101010, 50, 30)!!
        assertNotNull(fleet.color("lamp-1", 0x202020, 50, 30))
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            fleet.acceptReply("lamp-1", "192.168.1.21", 55444, error(id(a)), 31))
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            fleet.acceptReply("lamp-0", "192.168.1.21", 55444, error(id(a)), 32))
        assertTrue(fleet.snapshot("lamp-0")!!.safetyStopped)
        assertFalse(fleet.snapshot("lamp-1")!!.safetyStopped)
        assertNull(fleet.color("lamp-0", 0x303030, 50, 130))
        assertNotNull(fleet.color("lamp-1", 0x404040, 50, 130))
        assertEquals(1, fleet.snapshot("lamp-0")!!.framesAttempted)
        assertEquals(2, fleet.snapshot("lamp-1")!!.framesAttempted)
    }

    @Test fun spatialShowStopsEntireCohortAfterCorrelatedError() {
        val specs = (0..1).map { i ->
            ChromaOfflineLampSpec("bulb-" + i, ChromaUdpOfflineSession(
                "192.168.1." + (21 + i), methods(if (i == 0) 2 else 1)))
        }
        val fleet = ChromaUdpOfflineFleet(specs)
        specs.forEachIndexed { i, spec ->
            val endpoint = "192.168.1." + (21 + i)
            val fake = ChromaFakeDevice(client, if (i == 0) 2 else 1, endpoint)
            val hello = fleet.begin(spec.key, 0)!!
            val reply = fake.process(ChromaFakeDatagram(
                client, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(spec.key, endpoint, 55444, reply.payload, 20))
        }
        val show = ChromaSpatialOfflineShow(
            listOf(ChromaSpatialLamp("bulb-0", 0, 0),
                ChromaSpatialLamp("bulb-1", 900, 900)),
            ChromaSpatialSettings(pattern = ChromaSpatialPattern.HORIZONTAL_SWEEP,
                palette = listOf(0x123456), stepMs = 100),
            fleet, ChromaOfflineLatestValue(fleet))
        assertTrue(show.arm(30))
        val first = show.tick(30)
        assertEquals(2, first.size)
        val rejected = first.first { it.key == "bulb-0" }.frame
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            fleet.acceptReply("bulb-0", "192.168.1.21", 55444,
                error(id(rejected)), 40))
        assertTrue(show.tick(130).isEmpty())
        assertTrue(show.allQuarantinedOrStopped())
        assertEquals(1, fleet.snapshot("bulb-1")!!.framesAttempted)
    }

    @Test fun fourteenLampErrorStormIsBoundedAndNeverConsumesExtraFrames() {
        val specs = (0 until 14).map { i ->
            ChromaOfflineLampSpec("lamp-" + i, ChromaUdpOfflineSession(
                "192.168.1." + (21 + i), methods(if (i % 2 == 0) 2 else 1)))
        }
        val fleet = ChromaUdpOfflineFleet(specs)
        specs.forEachIndexed { i, spec ->
            val endpoint = "192.168.1." + (21 + i)
            val fake = ChromaFakeDevice(client, if (i % 2 == 0) 2 else 1, endpoint)
            val hello = fleet.begin(spec.key, 0)!!
            val reply = fake.process(ChromaFakeDatagram(
                client, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(spec.key, endpoint, 55444, reply.payload, 20))
        }
        val ids = specs.mapIndexed { i, spec ->
            id(fleet.color(spec.key, 0x101010 + i, 60, 30)!!)
        }
        repeat(1000) { n ->
            val index = n % 14
            assertEquals(ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY,
                fleet.acceptReply(specs[index].key, "192.168.1." + (21 + index),
                    55444, error(ids[index] + 1000 + n), 31))
        }
        assertEquals(14, specs.sumOf { fleet.snapshot(it.key)!!.framesAttempted })
        assertTrue(specs.all { !fleet.snapshot(it.key)!!.safetyStopped })
        assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
            fleet.acceptReply("lamp-7", "192.168.1.28", 55444, error(ids[7]), 32))
        assertEquals(1, specs.count { fleet.snapshot(it.key)!!.safetyStopped })
        assertEquals(14, specs.sumOf { fleet.snapshot(it.key)!!.framesAttempted })
    }
}
