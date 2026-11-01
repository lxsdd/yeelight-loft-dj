package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MicrophoneAnalysisGainTest {
    @Test fun unityIsByteIdenticalAndDefaultIsUnamplified() {
        val pcm = shortArrayOf(0, -32768, 32767, 10, -100, 1200)
        assertSame(pcm, MicrophoneAnalysisGain.apply(pcm,1f))
        assertEquals(1f,MicrophoneAnalysisGain.normalize(Float.NaN),0f)
        assertEquals(1f,MicrophoneAnalysisGain.normalize(Float.POSITIVE_INFINITY),0f)
    }

    @Test fun manualGainQuantizesAndSaturatesWithoutShortOverflow() {
        assertEquals(.5f,MicrophoneAnalysisGain.normalize(-4f),0f)
        assertEquals(4f,MicrophoneAnalysisGain.normalize(100f),0f)
        assertEquals(1.25f,MicrophoneAnalysisGain.normalize(1.24f),0f)
        val high = MicrophoneAnalysisGain.apply(shortArrayOf(0,1000,-1000,20000,-20000,
            Short.MAX_VALUE,Short.MIN_VALUE),2f)
        assertArrayEquals(shortArrayOf(0,2000,-2000,32767,-32768,32767,-32768),high)
    }

    @Test fun lowSignalBecomesDetectableButSilenceRemainsSilent() {
        val tiny = shortArrayOf(200, -200, 0)
        assertArrayEquals(shortArrayOf(800, -800, 0),
            MicrophoneAnalysisGain.apply(tiny,4f))
        assertArrayEquals(shortArrayOf(0,0,0),
            MicrophoneAnalysisGain.apply(shortArrayOf(0,0,0),4f))
    }

    @Test fun acceptanceTimerAndBoundAreAlwaysFinite() {
        assertEquals(15_000L, Color4MusicAcceptancePolicy.DURATION_MS)
        assertEquals(45,Color4MusicAcceptancePolicy.MAX_RGB_WRITES)
    }
}
