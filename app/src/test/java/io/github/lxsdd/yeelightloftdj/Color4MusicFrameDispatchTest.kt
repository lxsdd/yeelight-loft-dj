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

    @Test fun realMeasuredHighAccentIsBoundedToSingleReverseChannelAcceptance() {
        val analyzer = MusicAnalyzer(16000, 1024)
        analyzer.analyze(ShortArray(1024), 1000L)
        val samples = ShortArray(1024) { i ->
            (sin(2 * PI * 1875.0 * i / 16000.0) * 0.65 * 32767).toInt().toShort()
        }
        val accent = analyzer.analyze(samples, 1064L)
        assertEquals(MusicAccent.HIGH, accent.accent)
        assertFalse(accent.beat)
        assertTrue(Color4MusicFrameDispatch.isAudible(accent))
        assertEquals(MusicFrameDispatch.ACCENT,
            Color4MusicFrameDispatch.decide(accent, 0L, 500L,
                acceptanceTest = true, allStreaming = true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(accent, 0L, 499L,
                acceptanceTest = true, allStreaming = true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(accent, 0L, 1000L,
                acceptanceTest = false, allStreaming = true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(accent, 0L, 1000L,
                acceptanceTest = true, allStreaming = false))
        assertEquals(MusicFrameDispatch.ACCENT,
            Color4MusicFrameDispatch.decide(accent, 500L, 1000L,
                acceptanceTest = true, allStreaming = true))
    }

    @Test fun bassBeatHasPriorityOverSimultaneousHighAccentWithoutBurst() {
        val frame = MusicFrame(.24f,.58f,listOf(.6f,.25f,.12f,.1f,.35f,.18f),
            .41f,true,120,1200L, MusicAccent.HIGH)
        assertEquals(MusicFrameDispatch.BEAT,
            Color4MusicFrameDispatch.decide(frame,0L,350L,true,true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(frame,350L,450L,true,true))
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(frame,350L,450L,false,false))
    }

    @Test fun lowLevelAndFakeOnsetsNeverEscapeSafetyGate() {
        val fakeAccent = MusicFrame(.001f,.02f,listOf(.01f,.01f,.01f,.01f,.01f,.01f),
            .01f,false,null,100L,MusicAccent.HIGH)
        assertEquals(MusicFrameDispatch.NONE,
            Color4MusicFrameDispatch.decide(fakeAccent,0L,2000L,true,true))
        val metrics = Color4MusicTestMetrics()
            .withFrame(fakeAccent)
            .withWrites(2,0,MusicFrameDispatch.ACCENT)
            .withWrites(1,0,MusicFrameDispatch.BEAT)
            .withWrites(1,1,MusicFrameDispatch.AUDIO_ACTIVITY)
        assertEquals(2L,metrics.accentWriteAttempts)
        assertEquals(1L,metrics.beatWriteAttempts)
        assertEquals(1L,metrics.activityWriteAttempts)
        assertEquals(4L,metrics.writeAttempts)
        assertEquals(1L,metrics.writeFailures)
        assertTrue(metrics.finishedReport().contains("Akzent 2"))
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
