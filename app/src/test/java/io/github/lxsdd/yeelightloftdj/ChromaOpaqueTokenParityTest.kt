package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure in-memory Chroma v1/v2 regression. Token strings are untrusted opaque
 * OEM reply data, not numeric keys: their exact bytes must survive the client.
 * No sockets, hardware, firmware image or real device credentials are used.
 */
class ChromaOpaqueTokenParityTest {
    private val client = "192.168.1.10"
    private val lamp = "192.168.1.21"
    private val mixed = "AbCdEf0123456789aBcDeF0123456789"

    private fun support(version: Int): Set<String> =
        setOf("chroma", if (version == 2) "udp_sess_new" else "udp_new", "set_scene")

    private fun roundTripStart(
        version: Int, token: String = mixed,
    ): Pair<ChromaUdpOfflineSession, ChromaFakeDevice> {
        val session = ChromaUdpOfflineSession(lamp, support(version))
        val fake = ChromaFakeDevice(client, version, lamp, token)
        val first = session.begin(0)!!
        val reply = fake.process(ChromaFakeDatagram(client, 50000,
            first.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply(reply.senderIp, reply.senderPort, reply.payload, 20))
        return session to fake
    }

    @Test fun v1AndV2PreserveExactMixedCaseTokenForKeepaliveAndColor() {
        for (version in 1..2) {
            val (session, fake) = roundTripStart(version)
            val keepalive = session.keepAlive(10_020)!!
            assertEquals(mixed, JSONObject(keepalive.forSimulatorOnly())
                .getString("token"))
            val keepReply = fake.process(ChromaFakeDatagram(client, 50000,
                keepalive.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
                session.acceptReply(keepReply.senderIp, keepReply.senderPort,
                    keepReply.payload, 10_030))
            val color = session.previewColor(0xAABBCC, 80, 10_040)!!
            assertEquals(mixed, JSONObject(color.forSimulatorOnly())
                .getString("token"))
            assertNull(fake.process(ChromaFakeDatagram(client, 50000,
                color.forSimulatorOnly())))
            assertEquals(1, fake.acceptedColorFrames)
            assertEquals("ChromaPreviewFrame(redacted)", color.toString())
            assertFalse(color.toString().contains(mixed))
        }
    }

    @Test fun caseOnlyTokenMutationInKeepaliveReplyIsRejectedWithoutSpoilingRealAck() {
        for (version in 1..2) {
            val (session, fake) = roundTripStart(version)
            val keepalive = session.keepAlive(10_020)!!
            val validReply = fake.process(ChromaFakeDatagram(client, 50000,
                keepalive.forSimulatorOnly()))!!
            val forged = JSONObject(validReply.payload)
                .put("params", JSONObject().put("token", mixed.lowercase()))
            assertEquals(ChromaPreviewEvent.IGNORED_INVALID_REPLY,
                session.acceptReply(lamp, 55444, forged.toString(), 10_021))
            assertTrue(session.tokenHeld)
            assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
                session.acceptReply(validReply.senderIp, validReply.senderPort,
                    validReply.payload, 10_022))
            assertTrue(session.tokenHeld)
        }
    }

    @Test fun tokenFailureDoesNotAuthorizeReplayOrUnadvertisedScenes() {
        for (version in 1..2) {
            val session = ChromaUdpOfflineSession(lamp, support(version) - "set_scene")
            val fake = ChromaFakeDevice(client, version, lamp, mixed)
            val hello = session.begin(0)!!
            val ack = fake.process(ChromaFakeDatagram(client, 50000,
                hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                session.acceptReply(lamp, 55444, ack.payload, 20))
            assertNull(session.previewColor(0x010203, 70, 30))
            val keep = session.keepAlive(10_020)!!
            assertEquals(mixed, JSONObject(keep.forSimulatorOnly())
                .getString("token"))
            val realAck = fake.process(ChromaFakeDatagram(client, 50000,
                keep.forSimulatorOnly()))!!
            val forged = JSONObject(realAck.payload)
                .put("params", JSONObject().put("token", mixed.lowercase()))
            assertEquals(ChromaPreviewEvent.IGNORED_INVALID_REPLY,
                session.acceptReply(lamp, 55444, forged.toString(), 10_021))
            assertNull(session.previewColor(0x010203, 70, 10_022))
            assertEquals(ChromaPreviewEvent.ACCEPTED_KEEPALIVE,
                session.acceptReply(lamp, 55444, realAck.payload, 10_023))
            assertEquals(0, fake.acceptedColorFrames)
        }
    }

    @Test fun fourteenIndependentLanesRetainOpaqueTokensWithoutCrossTraffic() {
        val entries = (0 until 14).map { i ->
            val ip = "192.168.1.${21 + i}"
            val version = if (i % 2 == 0) 2 else 1
            val unique = i.toString().padStart(2, '0') +
                (if (i % 2 == 0) "AbCdEf0123456789aBcDeF01234567"
                 else "abcdef0123456789abcdef01234567")
            assertEquals(32, unique.length)
            Triple("lamp-$i", ChromaUdpOfflineSession(ip, support(version)),
                ChromaFakeDevice(client, version, ip, unique))
        }
        val fleet = ChromaUdpOfflineFleet(entries.map {
            ChromaOfflineLampSpec(it.first, it.second)
        })
        entries.forEach { (key, _, fake) ->
            val first = fleet.begin(key, 0)!!
            val ack = fake.process(ChromaFakeDatagram(client, 50000,
                first.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(key, ack.senderIp, ack.senderPort, ack.payload, 20))
        }
        val coalescer = ChromaOfflineLatestValue(fleet)
        entries.forEachIndexed { i, (key, _, fake) ->
            repeat(1000) { n ->
                assertTrue(coalescer.offer(key, i * 10000 + n, 70, 30))
            }
            val packet = coalescer.poll(key, 31)!!
            assertNull(fake.process(ChromaFakeDatagram(client, 50000,
                packet.forSimulatorOnly())))
            assertEquals(1, fake.acceptedColorFrames)
            assertEquals(1, fleet.snapshot(key)!!.framesAttempted)
            assertEquals(999L, coalescer.snapshot(key)!!.supersededSamples)
        }
        assertEquals(14, entries.sumOf { it.third.acceptedColorFrames })
    }

    @Test fun matchingColorRejectionStillClosesAndErasesMixedCaseToken() {
        for (version in 1..2) {
            val (session, fake) = roundTripStart(version)
            fake.rejectNextCommand = true
            val frame = session.previewColor(0x010203, 70, 30)!!
            val rejection = fake.process(ChromaFakeDatagram(client, 50000,
                frame.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR,
                session.acceptReply(lamp, 55444, rejection.payload, 40))
            assertFalse(session.tokenHeld)
            assertNull(session.previewColor(0x010204, 70, 50))
            assertNull(session.keepAlive(10_050))
            assertEquals(0, fake.acceptedColorFrames)
        }
    }
}
