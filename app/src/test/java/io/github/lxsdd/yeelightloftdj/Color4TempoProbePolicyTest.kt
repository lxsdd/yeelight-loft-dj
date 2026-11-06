package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4TempoProbePolicyTest {
    private fun frame(beat: Boolean = false) = MusicFrame(
        rms = .12f, peak = .45f,
        bands = listOf(.3f, .2f, .12f, .07f, .04f, .01f),
        bass = .2f, beat = beat, estimatedBpm = null, timeMs = 1000L,
    )

    @Test fun beatsAt130BpmAreNotBlockedByOneSecondBaselinePace() {
        val f = frame(beat = true)
        // 130 BPM => ~462 ms between real bass events.
        assertFalse(MinimalColor4ProbePolicy.canSend(f, 462L, 0L, 1))
        assertEquals(
            MusicFrameDispatch.BEAT,
            Color4TempoProbePolicy.decide(f, true, 462L, 0L, 1)
        )
        assertEquals(
            MusicFrameDispatch.BEAT,
            Color4TempoProbePolicy.decide(f, true, 924L, 462L, 2)
        )
    }

    @Test fun fastTrialCannotExceedHardwareBoundOrSendWithoutAudio() {
        val f = frame()
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(null, false, 1200L, 0L, 1))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, true, 8000L, 7000L, 0))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, true, 7000L, 6500L, 16))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, true, 300L, 0L, 0))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(
                f.copy(rms = .0001f), true, 1000L, 0L, 0))
    }

    @Test fun fallbackIsActivityOnlyAndSlowerThanMeasuredBassBeats() {
        val f = frame()
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, false, 700L, 0L, 1))
        assertEquals(MusicFrameDispatch.AUDIO_ACTIVITY,
            Color4TempoProbePolicy.decide(f, false, 1000L, 0L, 1))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, true, 400L, 0L, 0))
        assertEquals(MusicFrameDispatch.BEAT,
            Color4TempoProbePolicy.decide(f, true, 500L, 0L, 0))
        assertEquals(MusicFrameDispatch.NONE,
            Color4TempoProbePolicy.decide(f, true, -100L, 0L, 0))
    }

    @Test fun baselineStillUsesOriginalExactlyOneSecondEightFrameLimit() {
        val f = frame()
        assertTrue(MinimalColor4ProbePolicy.canSend(f, 1000L, 0L, 0))
        assertFalse(MinimalColor4ProbePolicy.canSend(f, 462L, 0L, 0))
        assertFalse(MinimalColor4ProbePolicy.canSend(f, 4000L, 2000L, 8))
        assertEquals(1000L, MinimalColor4ProbePolicy.MIN_FRAME_MS)
        assertEquals(8, MinimalColor4ProbePolicy.MAX_RGB_FRAMES)
        assertEquals(8_000L, MinimalColor4ProbePolicy.DURATION_MS)
    }

    @Test fun tempoExperimentAllowsAtMost16FramesButNeverNormalLanFallback() {
        assertEquals(16, Color4TempoProbePolicy.MAX_RGB_FRAMES)
        assertTrue(Color4TempoProbePolicy.MIN_BEAT_SPACING_MS >= 450L)
        assertTrue(Color4TempoProbePolicy.FALLBACK_ACTIVITY_SPACING_MS >= 1000L)
        assertTrue(Color4TempoProbePolicy.MAX_RGB_FRAMES < 40)
        // Color4TempoProbePolicy returns ONLY a decision, not a transport command.
    }
}
