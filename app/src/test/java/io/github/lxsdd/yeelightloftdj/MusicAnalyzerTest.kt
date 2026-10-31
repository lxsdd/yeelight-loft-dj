package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MusicAnalyzerTest {
    private fun tone(hz: Double, amplitude: Double = 0.8, size: Int = 2048): ShortArray =
        ShortArray(size) { i ->
            (sin(2 * PI * hz * i / 16000) * amplitude * 32767.0).toInt().toShort()
        }
    private val quiet = ShortArray(2048)

    @Test fun silenceProducesNoBeatsAndNoInventedBpm() {
        val a = MusicAnalyzer()
        repeat(32) { i ->
            val frame = a.analyze(quiet, i * 128L)
            assertFalse(frame.beat)
            assertNull(frame.estimatedBpm)
            assertEquals(0f, frame.rms, 0.00001f)
            assertTrue(frame.bands.all { it <= 0.001f })
        }
    }

    @Test fun detectsRealFftFrequencyBands() {
        val bass = MusicAnalyzer().analyze(tone(78.125), 500L)
        val high = MusicAnalyzer().analyze(tone(1875.0), 500L)
        assertTrue(bass.bands[0] > bass.bands[5])
        assertTrue(high.bands[4] > high.bands[0])
        assertTrue(bass.peak in 0.7f..0.9f)
    }

    @Test fun transientDetectorDebouncesAndResetsAfterSilence() {
        val a = MusicAnalyzer()
        repeat(8) { i -> a.analyze(quiet, i * 128L) }
        val first = a.analyze(tone(78.125), 1200L)
        assertTrue("Real bass transient expected", first.beat)
        val second = a.analyze(tone(78.125), 1328L)
        assertFalse("Same sustained tone must not retrigger", second.beat)
        val silence = a.analyze(quiet, 7500L)
        assertNull(silence.estimatedBpm)
    }

    @Test fun rejectsNonPowerOfTwoBuffersAndWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { MusicAnalyzer(16000, 1000) }
        assertThrows(IllegalArgumentException::class.java) {
            MusicAnalyzer().analyze(ShortArray(100), 100L)
        }
    }

    @Test fun commandPolicyRateLimitsAndRemainsOptIn() {
        val a = MusicFrame(0.25f, 0.7f, listOf(.7f,.3f,.1f,.1f,.1f,.1f),
            0.6f, true, null, 2400L)
        assertTrue(MusicLightPolicy.maySend(a, 0L, 1500L))
        assertFalse(MusicLightPolicy.maySend(a, 1000L, 2000L))
        assertFalse(MusicLightPolicy.maySend(a.copy(beat = false), 0L, 3000L))
        assertEquals(MusicLightPolicy.color(a, 0, 1L), MusicLightPolicy.color(a, 3, 1L))
        assertNotEquals(MusicLightPolicy.color(a, 0, 1L), MusicLightPolicy.color(a, 1, 1L))
    }
}
