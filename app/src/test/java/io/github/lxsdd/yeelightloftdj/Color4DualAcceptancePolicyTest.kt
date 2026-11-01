package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4DualAcceptancePolicyTest {
    private fun lamp(ip: String, model: String = "color4",
        methods: Set<String> = setOf("set_music","set_rgb")) =
        YeelightDevice(id=ip, ip=ip, model=model, methods=methods)
    private val two = listOf(lamp("192.168.178.21"),lamp("192.168.178.22"))

    @Test fun signedAuthorizationIsUnavailableInThisSourceRevision() {
        assertTrue(Color4DualAcceptancePolicy.LIVE_TEST_APPROVED)
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            signedTestApproved=false,selectedNames=setOf("Couchtisch","Flur")))
        assertNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            selectedNames=setOf("Couchtisch","Flur")))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            selectedNames=setOf("Couchtisch","Wellness")))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            selectedNames=setOf("Esstisch 1","Esstisch 2")))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),false,
            selectedNames=emptySet()))
    }
    @Test fun rejectsAllInvalidSelectionsAndQuarantinedPair() {
        assertNotNull(Color4DualAcceptancePolicy.refusal(emptyList(),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two.take(1),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two+lamp("192.168.178.23"),
            emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],two[0].copy(id="duplicate")),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,setOf(two[1].ip),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(two,emptySet(),true,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],lamp(two[1].ip,"unknown")),emptySet(),false,true))
        assertNotNull(Color4DualAcceptancePolicy.refusal(
            listOf(two[0],lamp(two[1].ip,methods=setOf("set_music"))),
            emptySet(),false,true))
    }
    @Test fun combinedBudgetIntervalsAndBothActiveSocketsAreMandatory() {
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,2,1))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,1,1))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,1000,0,3,3))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(0,500,0,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(500,999,2,2,2))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(500,1000,2,2,2))
        assertTrue(Color4DualAcceptancePolicy.permitsFrame(0,500,38,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,500,39,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0,500,40,2,2))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(1500,1000,0,2,2))
        assertEquals(10_000L,Color4DualAcceptancePolicy.DURATION_MS)
        assertEquals(40,Color4DualAcceptancePolicy.MAX_TOTAL_RGB_WRITES)
    }
    @Test fun simulatedHighFrequencyAudioNeverExceedsWholePairBudget() {
        val frame = MusicFrame(
            rms=.3f, peak=.7f,
            bands=listOf(.65f,.4f,.17f,.15f,.12f,.11f),
            bass=.55f, beat=true, estimatedBpm=120, timeMs=1000L
        )
        var lastSent = Long.MIN_VALUE / 2
        var total = 0L
        var events = 0
        for (elapsed in 0L..10_000L step 64) {
            val sockets = if (elapsed >= 7_000L) 1 else 2
            if (!Color4DualAcceptancePolicy.permitsFrame(
                    lastSent,elapsed,total,2,sockets)) continue
            val trigger = Color4MusicFrameDispatch.decide(
                frame,lastSent,elapsed,acceptanceTest=true,allStreaming=true
            )
            if (trigger == MusicFrameDispatch.NONE) continue
            lastSent = elapsed
            total += 2
            events++
        }
        assertTrue("Must generate some real bounded music frames",events >= 8)
        assertTrue("Total writes across BOTH bulbs", total <= 40)
        assertEquals(events * 2L,total)
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(
            lastSent,10_064L,total,2,1))
    }

    @Test fun bothChannelsMustRemainActiveEvenWhenOriginalAudioHasNoBassBeat() {
        val noBeat=MusicFrame(
            rms=.2f,peak=.4f,
            bands=listOf(.07f,.09f,.14f,.17f,.26f,.31f),
            bass=.076f,beat=false,estimatedBpm=null,timeMs=1000L,
            accent=MusicAccent.HIGH
        )
        assertEquals(MusicFrameDispatch.ACCENT,
            Color4MusicFrameDispatch.decide(
                noBeat,0L,500L,acceptanceTest=true,allStreaming=true
            ))
        assertFalse(Color4DualAcceptancePolicy.permitsFrame(0L,500L,2L,2,1))
    }

    @Test fun independentPerDeviceDiagnosticsNeverClaimOpticalAck() {
        val line=Color4DualAcceptancePolicy.perDeviceReport(
            listOf("192.168.178.21","192.168.178.22"),
            mapOf("192.168.178.21" to 9L,"192.168.178.22" to 9L),
            mapOf("192.168.178.22" to 1L))
        assertTrue(line.contains("...21 RGB 9, Fehler 0"))
        assertTrue(line.contains("...22 RGB 9, Fehler 1"))
    }
}
