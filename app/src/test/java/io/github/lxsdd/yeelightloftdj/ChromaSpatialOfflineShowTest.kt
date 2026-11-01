package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChromaSpatialOfflineShowTest {
    private val clientIp = "192.168.1.10"
    private val supportV1 = setOf("chroma", "udp_new", "set_scene")
    private val supportV2 = setOf("chroma", "udp_sess_new", "set_scene")

    private data class FakeLamp(
        val key: String, val ip: String, val fake: ChromaFakeDevice,
        val pos: ChromaSpatialLamp,
    )
    private data class Harness(
        val lamps: List<FakeLamp>,
        val fleet: ChromaUdpOfflineFleet,
        val coalescer: ChromaOfflineLatestValue,
        val show: ChromaSpatialOfflineShow,
    )

    private fun setup(count: Int = 14, handshakeCount: Int = count,
                      config: ChromaSpatialSettings = ChromaSpatialSettings(
                          stepMs = 100, distancePerStep = 183, bandWidth = 250)): Harness {
        val lamps = (0 until count).map { i ->
            val ip = "192.168.1." + (21 + i)
            val version = if (i % 2 == 0) 2 else 1
            FakeLamp("lamp-" + i, ip,
                ChromaFakeDevice(clientIp, version, ip,
                    (i + 1).toString(16).padStart(32, '0')),
                ChromaSpatialLamp("lamp-" + i, (i * 79) % 1001,
                    (i * 139) % 1001))
        }
        val fleet = ChromaUdpOfflineFleet(lamps.mapIndexed { i, lamp ->
            ChromaOfflineLampSpec(lamp.key,
                ChromaUdpOfflineSession(lamp.ip, if (i % 2 == 0) supportV2 else supportV1))
        })
        lamps.take(handshakeCount).forEach { lamp ->
            val hello = fleet.begin(lamp.key, 0)!!
            val token = lamp.fake.process(ChromaFakeDatagram(
                clientIp, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(lamp.key, token.senderIp, token.senderPort, token.payload, 20))
        }
        val coalescer = ChromaOfflineLatestValue(fleet)
        return Harness(lamps, fleet, coalescer,
            ChromaSpatialOfflineShow(lamps.map { it.pos }, config, fleet, coalescer))
    }

    @Test fun nonLinearRadialWaveUsesYCoordinateAndPhysicalDistance() {
        val points = listOf(
            ChromaSpatialLamp("mid", 500, 500),
            ChromaSpatialLamp("up", 500, 100),
            ChromaSpatialLamp("side", 100, 500),
            ChromaSpatialLamp("other", 200, 200))
        val config = ChromaSpatialSettings(
            palette = listOf(0xAA00FF, 0xFF4400),
            brightness = 90, distancePerStep = 200, bandWidth = 300)
        val result = ChromaSpatialRenderer.render(points, config, 1)
        assertEquals(result.getValue("up"), result.getValue("side"))
        assertNotEquals(result.getValue("mid"), result.getValue("up"))
        assertNotEquals(result.getValue("up"), result.getValue("other"))
        assertTrue(result.values.all { it.brightness in 1..90 })
    }

    @Test fun horizontalSweepKeepsSameXInSamePhaseIrrespectiveOfY() {
        val config = ChromaSpatialSettings(pattern = ChromaSpatialPattern.HORIZONTAL_SWEEP)
        val result = ChromaSpatialRenderer.render(listOf(
            ChromaSpatialLamp("a", 200, 10),
            ChromaSpatialLamp("b", 200, 900),
            ChromaSpatialLamp("c", 800, 10)), config, 1)
        assertEquals(result.getValue("a"), result.getValue("b"))
        assertNotEquals(result.getValue("a"), result.getValue("c"))
    }

    @Test fun invalidLayoutsAndUnsafeOptionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialLamp("bad", -1, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialLamp("", 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialSettings(stepMs = 99)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialSettings(palette = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialSettings(brightness = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialSettings(distancePerStep = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialRenderer.render(listOf(
                ChromaSpatialLamp("dup", 0, 0), ChromaSpatialLamp("dup", 10, 10)),
                ChromaSpatialSettings(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChromaSpatialRenderer.render(listOf(ChromaSpatialLamp("a", 0, 0)),
                ChromaSpatialSettings(), -1)
        }
    }

    @Test fun incompleteTokenPreflightFailsClosedForEntireGroup() {
        val h = setup(handshakeCount = 13)
        assertFalse(h.show.arm(30))
        assertEquals(ChromaSpatialShowState.STOPPED, h.show.state)
        assertTrue(h.show.allQuarantinedOrStopped())
        assertTrue(h.show.tick(31).isEmpty())
        assertFalse(h.show.arm(40))
        assertEquals(0, h.lamps.sumOf { it.fake.acceptedColorFrames })
    }

    @Test fun fourteenLaneSpatialFramesRemainVersionTokenIsolated() {
        val h = setup()
        assertTrue(h.show.arm(30))
        val frames = h.show.tick(30)
        assertEquals(14, frames.size)
        assertEquals(14, frames.map { it.key }.toSet().size)
        for (frame in frames) {
            val lamp = h.lamps.single { it.key == frame.key }
            val packet = JSONObject(frame.frame.forSimulatorOnly())
            assertEquals("set_scene", packet.getString("method"))
            assertTrue(packet.getJSONArray("params").getInt(2) in 1..100)
            assertNull(lamp.fake.process(ChromaFakeDatagram(
                clientIp, 51000, frame.frame.forSimulatorOnly())))
        }
        assertEquals(14, h.lamps.sumOf { it.fake.acceptedColorFrames })
        assertEquals(14, h.lamps.sumOf { h.fleet.snapshot(it.key)!!.framesAttempted })
        assertEquals(ChromaSpatialShowState.ACTIVE, h.show.state)
        assertTrue(h.show.tick(30).isEmpty()) // deduplicated same scene time
    }

    @Test fun oneQuarantinedLaneStopsEntireShowBeforeAnyNewFrames() {
        val h = setup()
        assertTrue(h.show.arm(30))
        val initial = h.show.tick(30)
        assertEquals(14, initial.size)
        assertTrue(h.fleet.quarantine(h.lamps[4].key))
        assertTrue(h.show.tick(130).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
        assertTrue(h.show.tick(230).isEmpty())
        assertEquals(14, h.lamps.sumOf { h.fleet.snapshot(it.key)!!.framesAttempted })
    }

    @Test fun oneLostSessionAfterArmStopsAllWithoutNewFrames() {
        val h = setup()
        assertTrue(h.show.arm(30))
        assertEquals(14, h.show.tick(30).size)
        assertTrue(h.coalescer.stop(h.lamps[8].key))
        assertTrue(h.show.tick(130).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
    }

    @Test fun backwardClockClosesAllAndForbidsRestart() {
        val h = setup()
        assertTrue(h.show.arm(100))
        assertEquals(14, h.show.tick(100).size)
        assertTrue(h.show.tick(99).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
        assertFalse(h.show.arm(110))
    }

    @Test fun fifteenSecondDeadlineIsHardAndNeverTriggersImplicitCleanupWrites() {
        val h = setup()
        assertTrue(h.show.arm(30))
        assertEquals(14, h.show.tick(30).size)
        assertTrue(h.show.tick(15030).isEmpty())
        assertTrue(h.show.allQuarantinedOrStopped())
        assertTrue(h.show.tick(16030).isEmpty())
        assertEquals(14, h.lamps.sumOf { h.fleet.snapshot(it.key)!!.framesAttempted })
    }

    @Test fun fourteenBulbsReachIndependentCeilingAndStopWithout46thFrame() {
        val h = setup(config = ChromaSpatialSettings(
            stepMs = 100, distancePerStep = 173, bandWidth = 210))
        assertTrue(h.show.arm(30))
        var emitted = 0
        repeat(45) { step ->
            val frames = h.show.tick(30L + step * 100L)
            emitted += frames.size
            assertEquals(14, frames.size)
            for (frame in frames) {
                val lamp = h.lamps.single { it.key == frame.key }
                lamp.fake.process(ChromaFakeDatagram(clientIp, 51000,
                    frame.frame.forSimulatorOnly()))
            }
        }
        assertEquals(630, emitted)
        assertEquals(630, h.lamps.sumOf { it.fake.acceptedColorFrames })
        assertTrue(h.show.allQuarantinedOrStopped())
        assertTrue(h.show.tick(4530).isEmpty())
        assertEquals(630, h.lamps.sumOf { h.fleet.snapshot(it.key)!!.framesAttempted })
    }

    @Test fun noKnightRiderOffClaimsAndNoRawTokensInSummaries() {
        val h = setup(2)
        assertTrue(h.show.arm(30))
        val output = h.show.tick(30)
        assertEquals(2, output.size)
        assertTrue(ChromaSpatialRenderer.render(h.lamps.map { it.pos },
            ChromaSpatialSettings(), 0).values.all { it.brightness >= 1 })
        assertFalse(output.toString().contains("00000000000000000000000000000001"))
        assertFalse(output.toString().contains("00000000000000000000000000000002"))
        assertTrue(output.toString().contains("redacted"))
        h.show.stop()
        assertTrue(h.show.allQuarantinedOrStopped())
    }

    @Test fun groupRefusesTokenizedSessionWithoutAdvertisedSceneCommand() {
        val ipA = "192.168.1.21"
        val ipB = "192.168.1.22"
        val specs = listOf(
            ChromaOfflineLampSpec("a", ChromaUdpOfflineSession(ipA, supportV2)),
            ChromaOfflineLampSpec("b", ChromaUdpOfflineSession(ipB,
                setOf("chroma", "udp_sess_new"))))
        val fleet = ChromaUdpOfflineFleet(specs)
        val fakes = mapOf("a" to ChromaFakeDevice(clientIp, 2, ipA),
            "b" to ChromaFakeDevice(clientIp, 2, ipB))
        for ((key, ip) in listOf("a" to ipA, "b" to ipB)) {
            val hello = fleet.begin(key, 0)!!
            val reply = fakes.getValue(key).process(ChromaFakeDatagram(
                clientIp, 51000, hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(key, ip, reply.senderPort, reply.payload, 20))
        }
        val show = ChromaSpatialOfflineShow(
            listOf(ChromaSpatialLamp("a", 0, 0),
                ChromaSpatialLamp("b", 500, 500)), ChromaSpatialSettings(),
            fleet, ChromaOfflineLatestValue(fleet))
        assertFalse(show.arm(30))
        assertTrue(show.allQuarantinedOrStopped())
        assertTrue(show.tick(30).isEmpty())
    }

    @Test fun spatialPreviewNeverUsesAnyRealSocketOrBackgroundTimer() {
        val declared = ChromaSpatialOfflineShow::class.java.declaredMethods
            .map { it.name.lowercase() }
        assertFalse(declared.any { "socket" in it || "network" in it || "thread" in it })
        assertEquals(ChromaSpatialShowState.NEW, setup(2).show.state)
    }
    @Test fun expiredOrBackwardClockCannotArmEvenWithCachedToken() {
        val expired = setup(2)
        assertFalse(expired.show.arm(15_000))
        assertTrue(expired.show.allQuarantinedOrStopped())
        assertTrue(expired.show.tick(15_001).isEmpty())
        val backward = setup(2)
        assertFalse(backward.show.arm(19)) // fake token replies at t=20
        assertTrue(backward.show.allQuarantinedOrStopped())
    }

    @Test fun asymmetricPacingDefersWholeFourteenLampBatchWithoutPartialFrames() {
        val h = setup(14)
        val busy = h.lamps[0].key
        assertNotNull(h.fleet.color(busy, 0x112233, 60, 30))
        assertTrue(h.show.arm(30))
        assertTrue(h.show.tick(30).isEmpty())
        assertEquals(1, h.fleet.snapshot(busy)!!.framesAttempted)
        assertEquals(0, h.lamps.drop(1).sumOf {
            h.fleet.snapshot(it.key)!!.framesAttempted
        })
        assertEquals(ChromaSpatialShowState.ACTIVE, h.show.state)
        val next = h.show.tick(130)
        assertEquals(14, next.size)
        assertEquals(14, next.map { it.key }.toSet().size)
        assertEquals(15, h.lamps.sumOf { h.fleet.snapshot(it.key)!!.framesAttempted })
    }

}
