package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** In-memory synthetic admission matrix; never uses any device or socket. */
class ChromaOfflineAdmissionSafetyTest {
    private val client = "192.168.1.10"
    private val v1 = setOf("chroma", "udp_new", "set_scene")
    private val v2 = setOf("chroma", "udp_sess_new", "set_scene")

    private data class H(
        val keys: List<String>,
        val fleet: ChromaUdpOfflineFleet,
        val buffer: ChromaOfflineLatestValue,
        val show: ChromaSpatialOfflineShow,
    )

    private fun setup(count: Int = 2, stepMs: Int = 100): H {
        val keys = (0 until count).map { "bulb-" + it }
        val specs = keys.mapIndexed { i, key ->
            ChromaOfflineLampSpec(key, ChromaUdpOfflineSession(
                "192.168.1." + (21 + i), if (i % 2 == 0) v2 else v1))
        }
        val fleet = ChromaUdpOfflineFleet(specs)
        specs.forEachIndexed { i, spec ->
            val ip = "192.168.1." + (21 + i)
            val fake = ChromaFakeDevice(client, if (i % 2 == 0) 2 else 1, ip,
                (i + 1).toString(16).padStart(32, '0'))
            val hello = fleet.begin(spec.key, 0)!!
            val reply = fake.process(ChromaFakeDatagram(client, 51000,
                hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(spec.key, reply.senderIp, reply.senderPort,
                    reply.payload, 20))
        }
        val buffer = ChromaOfflineLatestValue(fleet)
        val lamps = keys.mapIndexed { i, key ->
            ChromaSpatialLamp(key, if (i == 0) 0 else if (i == 1) 900
                else (i * 73) % 1001, (i * 139) % 1001)
        }
        val settings = ChromaSpatialSettings(
            pattern = ChromaSpatialPattern.HORIZONTAL_SWEEP,
            palette = listOf(0x123456), brightness = 80, bandWidth = 200,
            distancePerStep = 100, stepMs = stepMs)
        return H(keys, fleet, buffer,
            ChromaSpatialOfflineShow(lamps, settings, fleet, buffer))
    }

    @Test fun rejectsNonCanonicalOrNonUnicastSyntheticEndpoints() {
        for (bad in listOf(
            "", "host.local", "::1", "192.168.001.21", "192.168.1.021",
            "192.168.1.256", "192.168.1.21:55444", "192.168.1",
            "0.0.0.0", "127.0.0.1", "224.0.0.1", "255.255.255.255",
            "192.168.-1.21", "192.168.1.2a"
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                ChromaUdpOfflineSession(bad, v2)
            }
            if (bad.isNotEmpty()) assertFalse(error.message.orEmpty().contains(bad))
        }
    }

    @Test fun whitespaceCanonicalizationDoesNotCreateSecondQuotaLane() {
        val first = ChromaUdpOfflineSession("192.168.1.21", v2)
        val second = ChromaUdpOfflineSession(" 192.168.1.21 ", v1)
        assertThrows(IllegalArgumentException::class.java) {
            ChromaUdpOfflineFleet(listOf(
                ChromaOfflineLampSpec("a", first),
                ChromaOfflineLampSpec("b", second)))
        }
    }

    @Test fun canonicalSenderAcceptedAndWrongSenderOrPortRejected() {
        val session = ChromaUdpOfflineSession(" 192.168.1.21 ", v2)
        val fake = ChromaFakeDevice(client, 2, "192.168.1.21")
        val hello = session.begin(0)!!
        val reply = fake.process(ChromaFakeDatagram(client, 51000,
            hello.forSimulatorOnly()))!!
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            session.acceptReply("192.168.1.22", 55444, reply.payload, 10))
        assertEquals(ChromaPreviewEvent.IGNORED_FOREIGN_SENDER,
            session.acceptReply("192.168.1.21", 55443, reply.payload, 11))
        assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
            session.acceptReply("192.168.1.21", 55444, reply.payload, 20))
    }

    @Test fun freshPendingReadyThenNoChangeWithoutDuplicateFrame() {
        val h = setup()
        val key = h.keys[0]
        assertEquals(ChromaOfflinePollReadiness.NO_CHANGE,
            h.buffer.preflight(key, 30))
        assertTrue(h.buffer.offer(key, 0x123456, 70, 31))
        assertEquals(ChromaOfflinePollReadiness.READY,
            h.buffer.preflight(key, 32))
        assertNotNull(h.buffer.poll(key, 32))
        assertTrue(h.buffer.offer(key, 0x123456, 70, 130))
        assertEquals(ChromaOfflinePollReadiness.NO_CHANGE,
            h.buffer.preflight(key, 130))
        assertFalse(h.buffer.snapshot(key)!!.pending)
        assertEquals(1, h.fleet.snapshot(key)!!.framesAttempted)
    }

    @Test fun rateDeferralRetainsOnlyNewestSampleWithoutSpendingBudget() {
        val h = setup()
        val key = h.keys[0]
        assertTrue(h.buffer.offer(key, 10, 60, 30))
        assertNotNull(h.buffer.poll(key, 30))
        assertTrue(h.buffer.offer(key, 11, 60, 31))
        assertTrue(h.buffer.offer(key, 12, 60, 32))
        assertEquals(ChromaOfflinePollReadiness.RATE_DEFERRED,
            h.buffer.preflight(key, 33))
        assertEquals(1, h.fleet.snapshot(key)!!.framesAttempted)
        assertTrue(h.buffer.snapshot(key)!!.pending)
        assertEquals(ChromaOfflinePollReadiness.READY,
            h.buffer.preflight(key, 130))
        val packet = JSONObject(h.buffer.poll(key, 130)!!.forSimulatorOnly())
        assertEquals(12, packet.getJSONArray("params").getInt(1))
        assertEquals(2, h.fleet.snapshot(key)!!.framesAttempted)
    }

    @Test fun stalePendingDroppedWithoutLateReplay() {
        val h = setup()
        val key = h.keys[0]
        assertTrue(h.buffer.offer(key, 0xABCDEF, 50, 30))
        assertEquals(ChromaOfflinePollReadiness.STALE_DROPPED,
            h.buffer.preflight(key, 281))
        assertFalse(h.buffer.snapshot(key)!!.pending)
        assertEquals(1L, h.buffer.snapshot(key)!!.expiredSamples)
        assertNull(h.buffer.poll(key, 282))
        assertEquals(0, h.fleet.snapshot(key)!!.framesAttempted)
    }

    @Test fun staleAsymmetricCohortStopsBeforePartialOutput() {
        val h = setup(stepMs = 2000)
        assertNotNull(h.fleet.color(h.keys[0], 0x001122, 60, 30))
        assertTrue(h.show.arm(30))
        assertTrue(h.show.tick(30).isEmpty())
        assertEquals(1, h.fleet.snapshot(h.keys[0])!!.framesAttempted)
        assertEquals(0, h.fleet.snapshot(h.keys[1])!!.framesAttempted)
        assertTrue(h.show.tick(400).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
        assertEquals(0, h.fleet.snapshot(h.keys[1])!!.framesAttempted)
    }

    @Test fun unchangedLaneIsNoOpNotArtificialGroupRateBlock() {
        val h = setup()
        assertTrue(h.show.arm(30))
        assertEquals(2, h.show.tick(30).size)
        val second = h.show.tick(130)
        assertEquals(listOf(h.keys[0]), second.map { it.key })
        assertEquals(2, h.fleet.snapshot(h.keys[0])!!.framesAttempted)
        assertEquals(1, h.fleet.snapshot(h.keys[1])!!.framesAttempted)
        assertEquals(ChromaSpatialShowState.ACTIVE, h.show.state)
    }

    @Test fun quotaAfterArmStopsWholeCohortBeforeNewOutput() {
        val h = setup(14)
        assertTrue(h.show.arm(30))
        assertEquals(14, h.show.tick(30).size)
        h.fleet.quarantine(h.keys[7])
        assertTrue(h.show.tick(130).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
        assertEquals(14, h.keys.sumOf { h.fleet.snapshot(it)!!.framesAttempted })
    }

    @Test fun fourteenLaneBurstBoundedWithoutHiddenQueue() {
        val h = setup(14)
        assertTrue(h.show.arm(30))
        assertEquals(14, h.show.tick(30).size)
        repeat(5000) { n ->
            assertTrue(h.buffer.offer(h.keys[n % 14], n, 60, 31))
        }
        assertTrue(h.show.tick(31).isEmpty())
        assertEquals(14, h.keys.sumOf { h.fleet.snapshot(it)!!.framesAttempted })
        val next = h.show.tick(130)
        assertTrue(next.size <= 14)
        assertEquals(next.size, next.map { it.key }.toSet().size)
        assertTrue(h.keys.all { h.fleet.snapshot(it)!!.framesAttempted <= 2 })
    }

    @Test fun clockRollbackAndTokenLossStopBeforeNewBatch() {
        val backward = setup()
        assertTrue(backward.show.arm(30))
        assertEquals(2, backward.show.tick(30).size)
        assertTrue(backward.show.tick(29).isEmpty())
        assertTrue(backward.show.allQuarantinedOrStopped())

        val lost = setup()
        assertTrue(lost.show.arm(30))
        assertEquals(2, lost.show.tick(30).size)
        lost.fleet.stop(lost.keys[1])
        assertTrue(lost.show.tick(130).isEmpty())
        assertTrue(lost.show.allQuarantinedOrStopped())
    }

    @Test fun snapshotsRedactTokenEndpointAndColor() {
        val h = setup()
        assertTrue(h.buffer.offer(h.keys[0], 0xABCDEF, 70, 30))
        val report = h.buffer.snapshot(h.keys[0]).toString()
        assertFalse(report.contains("192.168.1.21"))
        assertFalse(report.contains("abcdef", ignoreCase = true))
        assertFalse(report.contains("00000000000000000000000000000001"))
    }
}
