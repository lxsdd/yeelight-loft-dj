package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MusicPreflightSequenceTest {
    private fun bulb(n:Int)=YeelightDevice(
        id="id-"+n, ip="192.168.178."+n, model="color4",
        methods=setOf("set_music","set_rgb")
    )
    @Test fun preservesOrderAndHasNoParallelOrRepeatedReads() {
        val selected=listOf(bulb(135),bulb(136),bulb(137))
        val traffic=mutableListOf<String>()
        var inFlight=0
        val got=MusicPreflightSequence.capture(selected) { device ->
            inFlight++
            assertEquals(1,inFlight)
            traffic += device.ip
            inFlight--
            device.ip.substringAfterLast('.')
        }
        assertEquals(selected.map { it.ip },traffic)
        assertEquals(3,got.size)
        assertEquals("136",got[selected[1].ip])
    }

    @Test fun firstQuotaRefusalStopsBeforeThirdBulbNoRetries() {
        val traffic=mutableListOf<String>()
        val selected=listOf(bulb(135),bulb(136),bulb(137))
        val failure=runCatching {
            MusicPreflightSequence.capture(selected) { device ->
                traffic += device.ip
                if(device.ip.endsWith(".136"))
                    error("client quota exceeded")
                "status"
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("client quota exceeded"))
        assertEquals(listOf(selected[0].ip,selected[1].ip),traffic)
    }

    @Test fun duplicateIpIsRejectedBeforeAnyNetworkCommand() {
        val one=bulb(136)
        var requests=0
        assertTrue(runCatching {
            MusicPreflightSequence.capture(listOf(one,one.copy(id="duplicate"))) {
                requests++
                "unreachable"
            }
        }.isFailure)
        assertEquals(0,requests)
    }

    @Test fun emptyAndSingleSelectionDoNotContactOtherBulbs() {
        assertTrue(MusicPreflightSequence.capture(emptyList<YeelightDevice>()) { error("should not run") }.isEmpty())
        val events=mutableListOf<String>()
        val selected=bulb(136)
        assertEquals(mapOf(selected.ip to true),
            MusicPreflightSequence.capture(listOf(selected)) {
                events+=it.ip
                true
            })
        assertEquals(listOf(selected.ip),events)
    }
}
