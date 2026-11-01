package io.github.lxsdd.yeelightloftdj

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Offline 14-bulb loft-style cohort. Validates host safety, not real Wi-Fi
 * delivery, optically verified synchrony or firmware throughput.
 */
class ChromaOfflineCohortQualificationTest {
    private val clientIp = "192.168.1.10"
    private val v1 = setOf("chroma", "udp_new", "set_scene")
    private val v2 = setOf("chroma", "udp_sess_new", "set_scene")

    private data class Lamp(val key: String, val ip: String, val fake: ChromaFakeDevice)
    private data class Harness(
        val fleet: ChromaUdpOfflineFleet,
        val buffer: ChromaOfflineLatestValue,
        val lamps: List<Lamp>,
    )

    private fun setup(): Harness {
        val lamps = (0 until 14).map { index ->
            val ip = "192.168.1." + (21 + index)
            val version = if (index % 2 == 0) 2 else 1
            Lamp("bulb-$index", ip, ChromaFakeDevice(clientIp, version, ip,
                (index + 1).toString(16).padStart(32, '0')))
        }
        val fleet = ChromaUdpOfflineFleet(lamps.mapIndexed { index, lamp ->
            ChromaOfflineLampSpec(lamp.key, ChromaUdpOfflineSession(
                lamp.ip, if (index % 2 == 0) v2 else v1))
        })
        lamps.forEach { lamp ->
            val hello = fleet.begin(lamp.key, 0)!!
            val response = lamp.fake.process(ChromaFakeDatagram(clientIp, 51000,
                hello.forSimulatorOnly()))!!
            assertEquals(ChromaPreviewEvent.ACCEPTED_TOKEN,
                fleet.acceptReply(lamp.key, response.senderIp, response.senderPort,
                    response.payload, 20))
        }
        return Harness(fleet, ChromaOfflineLatestValue(fleet), lamps)
    }

    private fun deliver(lamp: Lamp, frame: ChromaPreviewFrame) {
        assertNull(lamp.fake.process(ChromaFakeDatagram(clientIp, 51000,
            frame.forSimulatorOnly())))
    }

    @Test fun fourteenBulbsCoalesce14000InputsInto14Frames() {
        val h = setup()
        h.lamps.forEachIndexed { index, lamp ->
            repeat(1000) { n ->
                assertTrue(h.buffer.offer(lamp.key, index * 10000 + n, 50, 30))
            }
        }
        h.lamps.forEachIndexed { index, lamp ->
            val frame = h.buffer.poll(lamp.key, 31)!!
            assertEquals(index * 10000 + 999, JSONObject(frame.forSimulatorOnly())
                .getJSONArray("params").getInt(1))
            deliver(lamp, frame)
            assertEquals(1, lamp.fake.acceptedColorFrames)
            assertEquals(999L, h.buffer.snapshot(lamp.key)!!.supersededSamples)
            assertEquals(1, h.fleet.snapshot(lamp.key)!!.framesAttempted)
        }
        assertEquals(14, h.lamps.sumOf { it.fake.acceptedColorFrames })
    }

    @Test fun tokenConfusionDoesNotConsumeAnotherLampsValidSequenceId() {
        val h = setup()
        val a = h.lamps[0]
        val b = h.lamps[2] // v2, distinct synthetic tokens
        assertTrue(h.buffer.offer(a.key, 0xABCD01, 65, 30))
        assertTrue(h.buffer.offer(b.key, 0xABCD02, 65, 30))
        val frameA = h.buffer.poll(a.key, 31)!!
        val frameB = h.buffer.poll(b.key, 31)!!
        deliver(b, frameA) // rejected: correct method and id, wrong token
        assertEquals(0, b.fake.acceptedColorFrames)
        deliver(b, frameB)
        deliver(a, frameA)
        assertEquals(1, a.fake.acceptedColorFrames)
        assertEquals(1, b.fake.acceptedColorFrames)
    }

    @Test fun quarantineStopsOnlySelectedLampAndKeepsThirteenIndependent() {
        val h = setup()
        val affected = h.lamps[3]
        assertTrue(h.buffer.offer(affected.key, 101, 50, 30))
        assertTrue(h.buffer.quarantine(affected.key))
        assertNull(h.buffer.poll(affected.key, 31))
        assertFalse(h.buffer.offer(affected.key, 102, 50, 32))
        h.lamps.filterNot { it.key == affected.key }.forEachIndexed { index, lamp ->
            assertTrue(h.buffer.offer(lamp.key, 0x101010 + index, 60, 33))
            deliver(lamp, h.buffer.poll(lamp.key, 34)!!)
        }
        assertEquals(0, affected.fake.acceptedColorFrames)
        assertEquals(13, h.lamps.sumOf { it.fake.acceptedColorFrames })
        assertTrue(h.buffer.snapshot(affected.key)!!.terminal)
    }

    @Test fun everyLaneEnforcesExact45FrameCeilingWithoutSharedBudget() {
        val h = setup()
        repeat(45) { n ->
            val now = 30L + n * 100L
            h.lamps.forEachIndexed { index, lamp ->
                assertTrue(h.buffer.offer(lamp.key, 100000 + index * 1000 + n,
                    50, now))
                deliver(lamp, h.buffer.poll(lamp.key, now)!!)
            }
        }
        h.lamps.forEach { lamp ->
            assertEquals(45, lamp.fake.acceptedColorFrames)
            assertEquals(45, h.fleet.snapshot(lamp.key)!!.framesAttempted)
            assertTrue(h.buffer.snapshot(lamp.key)!!.terminal)
            assertFalse(h.buffer.snapshot(lamp.key)!!.pending)
            assertFalse(h.buffer.offer(lamp.key, 123, 50, 5000))
            assertNull(h.buffer.poll(lamp.key, 5100))
        }
        assertEquals(630, h.lamps.sumOf { it.fake.acceptedColorFrames })
    }
}
