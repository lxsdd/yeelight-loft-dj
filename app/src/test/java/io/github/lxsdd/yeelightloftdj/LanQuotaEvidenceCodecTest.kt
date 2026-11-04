package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class LanQuotaEvidenceCodecTest {
    private val ip = "192.168.178.136"

    @Test fun roundTripPreservesMethodTimestampAndOwnCommandCount() {
        val result=LanQuotaEvidenceCodec.decode(
            LanQuotaEvidenceCodec.append(null,ip,"get_prop",1,1700000000000L))
        assertEquals(listOf(LanQuotaEvidence(ip,"get_prop",1,1700000000000L)), result)
    }

    @Test fun onlyAffectedIpIsReplacedWhenAnotherIncidentArrives() {
        val a=LanQuotaEvidenceCodec.append(null,"192.168.178.135","set_rgb",4,100L)
        val b=LanQuotaEvidenceCodec.append(a,ip,"get_prop",2,200L)
        val c=LanQuotaEvidenceCodec.append(b,ip,"set_music",3,300L)
        val found=LanQuotaEvidenceCodec.decode(c)
        assertEquals(listOf("192.168.178.135",ip),found.map{it.ip})
        assertEquals("set_music",found.last().method)
        assertEquals(3,found.last().appAdmittedLastMinute)
    }

    @Test fun boundedHistoryKeepsTwelveNewestIpsWithoutReplayOrNetwork() {
        var json:String?=null
        for(n in 1..32) json=LanQuotaEvidenceCodec.append(
            json,"192.168.178."+n,"get_prop",n%24,1000L+n)
        val all=LanQuotaEvidenceCodec.decode(json)
        assertEquals(12,all.size)
        assertEquals("192.168.178.21",all.first().ip)
        assertEquals("192.168.178.32",all.last().ip)
    }

    @Test fun garbageOversizeAndVersionMismatchCannotForgeHistory() {
        val invalid=listOf("garbage","{}", " ".repeat(8193),
            """{"version":2,"events":[]}""",
            """{"version":1,"events":[{"ip":"example.com","method":"get_prop","count":0,"time":10}]}""")
        invalid.forEach {
            assertTrue(LanQuotaEvidenceCodec.decode(it).isEmpty())
            assertEquals(1,LanQuotaEvidenceCodec.decode(
                LanQuotaEvidenceCodec.append(it,ip,"get_prop",1,20L)).size)
        }
    }

    @Test fun invalidMethodOrNonLocalIpNeverPersists() {
        assertTrue(runCatching {
            LanQuotaEvidenceCodec.append(null,ip,"get_prop;token",1,10L)
        }.isFailure)
        assertTrue(runCatching {
            LanQuotaEvidenceCodec.append(null,ip,"get_prop",25,10L)
        }.isFailure)
        assertTrue(runCatching {
            LanQuotaEvidenceCodec.append(null,"8.8.8.8","get_prop",1,10L)
        }.isFailure)
    }
}
