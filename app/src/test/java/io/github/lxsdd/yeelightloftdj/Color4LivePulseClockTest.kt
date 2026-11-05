package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4LivePulseClockTest {
    private val audible = MusicFrame(
        .13f,.45f,listOf(.4f,.3f,.15f,.09f,.06f,.02f),
        .4f,true,130,1_000L
    )

    @Test fun observed130BpmAndMissingBeatProduceExplicitlyLabeledGridPulses() {
        val clock = Color4LivePulseClock(0)
        assertEquals(LiveColorPulse.DETECTED_BEAT,clock.decide(audible,1,0L))
        assertEquals(LiveColorPulse.NONE,clock.decide(audible.copy(beat=false),1,200L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),1,465L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),1,930L))
        assertEquals(LiveColorPulse.DETECTED_BEAT,clock.decide(audible,2,1400L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),2,1865L))
    }

    @Test fun noBpmMeansNoUnmeasuredGridOrInventedBeat() {
        val clock = Color4LivePulseClock(0)
        val unknown = audible.copy(estimatedBpm=null,beat=false)
        assertEquals(LiveColorPulse.AUDIBLE_FALLBACK,clock.decide(unknown,0,0L))
        assertEquals(LiveColorPulse.NONE,clock.decide(unknown,0,450L))
        assertEquals(LiveColorPulse.AUDIBLE_FALLBACK,clock.decide(unknown,0,1_250L))
    }

    @Test fun silenceNeverEmitsGridAndPredictionExpiresAfterThreeMissedBeats() {
        val clock = Color4LivePulseClock(0)
        assertEquals(LiveColorPulse.DETECTED_BEAT,clock.decide(audible,1,0L))
        val silence=audible.copy(rms=0f,peak=0f,beat=false)
        assertEquals(LiveColorPulse.NONE,clock.decide(silence,1,465L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),1,465L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),1,925L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible.copy(beat=false),1,1390L))
        assertNotEquals(LiveColorPulse.BPM_GRID,
            clock.decide(audible.copy(beat=false),1,1850L))
    }

    @Test fun noSecondPhysicalLanPathIsPartOfLivePulseClock() {
        assertTrue(Color4LivePulseClock.MIN_SEND_SPACING_MS >= 250L)
        assertTrue(Color4LivePulseClock.MIN_SEND_SPACING_MS < 460L)
        assertTrue(Color4LivePulseClock.FALLBACK_SPACING_MS >= 1000L)
    }

    @Test fun newBeatAlwaysResynchronizesTempoGridWithoutDuplicateNearbyWrite() {
        val clock = Color4LivePulseClock(0)
        assertEquals(LiveColorPulse.DETECTED_BEAT,clock.decide(audible,1,0L))
        assertEquals(LiveColorPulse.BPM_GRID,clock.decide(audible,1,465L))
        assertEquals(LiveColorPulse.NONE,clock.decide(audible,2,480L))
        assertEquals(LiveColorPulse.BPM_GRID,
            clock.decide(audible.copy(beat=false),2,950L))
    }
}
