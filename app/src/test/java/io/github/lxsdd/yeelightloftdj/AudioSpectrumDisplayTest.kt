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
        // 1875 Hz belongs to the actual 1400–3200 Hz presence band
        // (index 4), NOT the >3200 Hz band (index 5). FFT leakage in
        // the last band should not be boosted above the noise floor.
        assertTrue(frame.bands[4] > .018f)
        assertTrue(frame.displayBands[4] >= frame.bands[4])
        assertEquals(6,frame.displayBands.size)
        assertEquals(MusicAccent.HIGH,frame.accent)
        val silence=analyzer.analyze(ShortArray(1024),1128L)
        assertTrue(silence.displayBands.all { it==0f })
        assertFalse(silence.beat)
    }
    @Test fun realWeakTrebleBelowOldDisplayFloorIsNowVisible() {
        val measured = listOf(.4f, .2f, .08f, .04f, .012f, .0064f)
        val display = AudioSpectrumDisplay()
        val visible = display.observe(measured, .08f, 1000L)
        // Old hard floor .018 made presence and treble always zero.
        assertTrue("Measured presence below old floor must be visible", visible[4] > .10f)
        assertTrue("Measured treble below old floor must be visible", visible[5] > .10f)
        assertEquals(.0064f, measured[5], 0f)
        assertTrue("No invented bands", display.observe(
            listOf(.3f,.2f,.1f,.02f,0f,0f),.08f,1100L)[5] == 0f)
    }

    @Test fun noSoundAndSubThresholdNoiseCannotProduceVisibleTreble() {
        val display = AudioSpectrumDisplay()
        val low = listOf(.002f,.002f,.002f,.002f,.002f,.002f)
        assertEquals(0f, display.observe(low,.04f,1000L)[5],0f)
        assertEquals(0f, display.observe(List(6){.2f},.0001f,1100L)[5],0f)
    }

    @Test fun silenceNaNAndTemporalResetRemainFinite() {
        val display=AudioSpectrumDisplay()
        display.observe(List(6){.2f},.2f,1000L)
        assertTrue(display.observe(List(6){.2f},0f,1064L).all { it==0f })
        assertTrue(display.observe(listOf(Float.NaN,1f,0f,-1f,0f,0f),
            .2f,100L).all { it.isFinite() && it in 0f..1f })
    }
}
