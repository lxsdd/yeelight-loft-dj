package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class Color4MusicFrameDispatchTest {
    private fun steadyTone(): MusicFrame {
        val samples = ShortArray(2048) { i ->
            (sin(2.0 * PI * 1875.0 * i / 16000.0) * 0.42 * 32767).toInt().toShort()
        }
        val analyzer = MusicAnalyzer()
        analyzer.analyze(samples, 1_000L)
        return analyzer.analyze(samples, 1_128L)
    }

    @Test fun realMeasuredNonBeatSignalDispatchesOnlyInExplicitReverseChannelAcceptance() {
        val frame = steadyTone()
        assertFalse("Sustained treble tone is not a bass beat", frame.beat)
        assertTrue(Color4MusicFrameDispatch.isAudible(frame))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(frame, 0, 1_000,
                acceptanceTest = false, allStreaming = true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(frame, 0, 1_000,
                acceptanceTest = true, allStreaming = false))
        assertEquals(MusicFrameDispatch.AUDIO_ACTIVITY,
            Color4MusicFrameDispatch.decide(frame, 0, 1_000,
                acceptanceTest = true, allStreaming = true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(frame, 1_000, 1_500,
                acceptanceTest = true, allStreaming = true))
        assertEquals(MusicFrameDispatch.AUDIO_ACTIVITY,
            Color4MusicFrameDispatch.decide(frame, 1_000, 2_000,
                acceptanceTest = true, allStreaming = true))
    }

    @Test fun silenceAndNoiseAreNeverTranslatedIntoFakeBeatsOrColorFrames() {
        val silent = MusicAnalyzer().analyze(ShortArray(2048), 1000L)
        val noise = MusicFrame(0.001f, 0.02f,
            listOf(0.01f,0.01f,0.01f,0.01f,0.01f,0.01f),
            0.01f, false, null, 1000L)
        for (frame in listOf(silent,noise)) {
            assertFalse(Color4MusicFrameDispatch.isAudible(frame))
            assertEquals(MusicFrameDispatch.NONE,
                Color4MusicFrameDispatch.decide(frame, 0L, 20_000L, true, true))
        }
    }

    @Test fun realBeatStillUsesFastStreamAndOrdinaryShowRemainsBeatOnly() {
        val beat = MusicFrame(0.3f,0.6f,
            listOf(.7f,.4f,.1f,.1f,.1f,.1f),
            .61f,true,120,1000L)
        assertEquals(MusicFrameDispatch.BEAT,
            Color4MusicFrameDispatch.decide(beat,0,350,false,true))
        assertEquals(MusicFrameDispatch.BEAT,
            Color4MusicFrameDispatch.decide(beat,0,1500,false,false))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(beat,0,350,false,false))
        assertEquals(MusicFrameDispatch.BEAT,
            Color4MusicFrameDispatch.decide(beat,0,350,true,true))
    }

    @Test fun diagnosticsDistinguishNoAudioNoBeatsAndUnverifiedWrites() {
        val quiet = MusicAnalyzer().analyze(ShortArray(2048), 1000L)
        val noisy = steadyTone()
        val none = Color4MusicTestMetrics()
        assertTrue(none.finishedReport().contains("Keine Audioframes"))
        val silent = none.withFrame(quiet)
        assertTrue(silent.finishedReport().contains("kein ausreichend lautes"))
        val audible = silent.withFrame(noisy)
        assertEquals(0L, audible.beats)
        assertTrue(audible.finishedReport().contains("kein RGB-Sendeversuch"))
        val wrote = audible.withWrites(1,0)
        assertEquals(1L,wrote.writeAttempts)
        assertTrue(wrote.finishedReport().contains("Senden ist keine optische Bestätigung"))
        val failed = audible.withWrites(1,1)
        assertTrue(failed.finishedReport().contains("Alle RGB-Sendeversuche"))
    }
}
