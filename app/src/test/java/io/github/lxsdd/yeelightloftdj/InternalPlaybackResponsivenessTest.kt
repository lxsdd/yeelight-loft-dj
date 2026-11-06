package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class InternalPlaybackResponsivenessTest {
    private fun tone(hz: Double, size: Int = 1024) = ShortArray(size) { i ->
        (sin(2.0 * PI * hz * i / 16000.0) * 0.65 * 32767).toInt().toShort()
    }

    @Test fun authorizedPlaybackCanAnalyzeRealMeasuredAudioEvery64Ms() {
        val analyzer = MusicAnalyzer(16000, 1024)
        val first = analyzer.analyze(ShortArray(1024), 1000L)
        val high = analyzer.analyze(tone(1875.0), 1064L)
        assertEquals(1024, analyzer.fftSize)
        assertFalse(first.beat)
        assertTrue(high.rms > .20f)
        assertEquals(MusicAccent.HIGH, high.accent)
        // Higher-frequency activity is not silently promoted into a bass beat.
        assertFalse(high.beat)
    }

    @Test fun lowMidAndHighAttacksRemainSeparateFromBeatAndFromConstantTones() {
        val frequencies = listOf(78.125 to MusicAccent.LOW,
            500.0 to MusicAccent.MID, 1875.0 to MusicAccent.HIGH)
        for ((hz, expected) in frequencies) {
            val analyzer = MusicAnalyzer(16000, 1024)
            analyzer.analyze(ShortArray(1024), 1000L)
            val attack = analyzer.analyze(tone(hz), 1064L)
            assertEquals("Accent classification for $hz Hz", expected, attack.accent)
            assertNull("Steady frequency should not create repeated fake attacks",
                analyzer.analyze(tone(hz), 1128L).accent)
        }
    }

    @Test fun silentAndLowSignalCannotGenerateFakeAccents() {
        val analyzer = MusicAnalyzer(16000, 1024)
        repeat(20) {
            val sample = analyzer.analyze(ShortArray(1024), 1000 + 64L * it)
            assertNull(sample.accent)
            assertFalse(sample.beat)
        }
    }

    @Test fun silenceClassificationIsTimeBasedNotFrameCountBased() {
        for (frameLength in listOf(64L,128L)) {
            val signal = PlaybackSignalWatch()
            var status = PlaybackSignalState.QUIET
            for (time in 0L..2496L step frameLength) {
                status = signal.observe(0f, time)
                assertEquals(PlaybackSignalState.QUIET,status)
            }
            assertEquals(PlaybackSignalState.NO_SIGNAL,signal.observe(0f, 2560L))
            assertEquals(PlaybackSignalState.AUDIBLE,signal.observe(.012f,2624L))
            assertEquals(PlaybackSignalState.QUIET,signal.observe(0f,2688L))
        }
    }

    @Test fun timeRollbackAndInvalidInputDoNotImplyCapturedMusic() {
        val signal = PlaybackSignalWatch()
        assertEquals(PlaybackSignalState.QUIET,signal.observe(Float.NaN,2000L))
        assertEquals(PlaybackSignalState.QUIET,signal.observe(-1f,1990L))
        assertEquals(PlaybackSignalState.AUDIBLE,signal.observe(.02f,2050L))
        assertEquals(PlaybackSignalState.QUIET,signal.observe(0f,2100L))
    }
}
