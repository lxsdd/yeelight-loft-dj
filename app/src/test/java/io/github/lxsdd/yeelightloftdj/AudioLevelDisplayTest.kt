package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class AudioLevelDisplayTest {
    @Test fun observedThreeToFourPercentPcmIsNotAThreePercentLoudnessClaim() {
        val three = AudioLevelDisplay.from(.03f, .12f)
        val four = AudioLevelDisplay.from(.04f, .14f)
        assertEquals(-30.46f, three.rmsDbfs!!, .15f)
        assertEquals(-27.96f, four.rmsDbfs!!, .15f)
        assertEquals("-30 dBFS", three.rmsLabel)
        assertEquals("-28 dBFS", four.rmsLabel)
        assertTrue(three.displayLevel in 0.5f..0.6f)
        assertTrue(four.displayLevel in 0.5f..0.65f)
        assertTrue(four.displayLevel > three.displayLevel)
        assertEquals("-18 dBFS", three.peakLabel)
    }

    @Test fun silenceQuietFullscaleAndOutOfRangeAreBounded() {
        val silent = AudioLevelDisplay.from(0f,0f)
        assertEquals("−∞ dBFS", silent.rmsLabel)
        assertEquals(0f, silent.displayLevel, 0f)
        assertEquals(0f, AudioLevelDisplay.from(.0001f,.001f).displayLevel,0f)
        assertEquals(1f, AudioLevelDisplay.from(1f,1f).displayLevel,0f)
        assertEquals(1f, AudioLevelDisplay.from(2f,2f).displayLevel,0f)
        assertEquals("0 dBFS", AudioLevelDisplay.from(2f,2f).peakLabel)
    }

    @Test fun invalidAmplitudeCannotLeakNanIntoComposeProgress() {
        val nan = AudioLevelDisplay.from(Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals("–", nan.rmsLabel)
        assertEquals("–", nan.peakLabel)
        assertEquals(0f, nan.displayLevel,0f)
        val negative = AudioLevelDisplay.from(-.1f,-.1f)
        assertEquals("−∞ dBFS", negative.rmsLabel)
        assertEquals(0f,negative.displayLevel,0f)
    }

    @Test fun presentationDoesNotTouchAudioAnalysisOrBeatDecisions() {
        val samples = ShortArray(2048)
        val frame = MusicAnalyzer().analyze(samples,1234L)
        val meter = AudioLevelDisplay.from(frame.rms,frame.peak)
        assertFalse(frame.beat)
        assertEquals("−∞ dBFS",meter.rmsLabel)
        assertEquals(0f,meter.displayLevel,0f)
    }
}
