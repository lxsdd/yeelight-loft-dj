package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class AudioSpectrumDisplayTest {
    @Test fun weakButMeasuredTreblesBecomeVisibleWithoutChangingAcousticInputs() {
        val measured=listOf(.65f,.30f,.14f,.11f,.08f,.09f)
        val result=AudioSpectrumDisplay().observe(measured,.2f,1000L)
        assertEquals(.09f,measured[5],0f)
        assertTrue(result[5] > .65f)
        assertTrue(result[4] > .50f)
        assertTrue(result.all { it in 0f..1f })
    }
    @Test fun realPcmSpectrumIsVisuallyScaledWithoutCreatingFakeBeat() {
        val analyzer=MusicAnalyzer(16000,1024)
        analyzer.analyze(ShortArray(1024),1000L)
        val pcm=ShortArray(1024) { i ->
            (kotlin.math.sin(2.0*kotlin.math.PI*1875.0*i/16000.0) *
                .42 * 32767).toInt().toShort()
        }
        val frame=analyzer.analyze(pcm,1064L)
        assertFalse(frame.beat)
        assertTrue(frame.displayBands[5] >= frame.bands[5])
        assertEquals(6,frame.displayBands.size)
        assertEquals(MusicAccent.HIGH,frame.accent)
        val silence=analyzer.analyze(ShortArray(1024),1128L)
        assertTrue(silence.displayBands.all { it==0f })
        assertFalse(silence.beat)
    }
    @Test fun silenceNaNAndTemporalResetRemainFinite() {
        val display=AudioSpectrumDisplay()
        display.observe(List(6){.2f},.2f,1000L)
        assertTrue(display.observe(List(6){.2f},0f,1064L).all { it==0f })
        assertTrue(display.observe(listOf(Float.NaN,1f,0f,-1f,0f,0f),
            .2f,100L).all { it.isFinite() && it in 0f..1f })
    }
}
