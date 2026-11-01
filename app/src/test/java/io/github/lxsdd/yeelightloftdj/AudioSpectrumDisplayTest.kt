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
    @Test fun silenceNaNAndTemporalResetRemainFinite() {
        val display=AudioSpectrumDisplay()
        display.observe(List(6){.2f},.2f,1000L)
        assertTrue(display.observe(List(6){.2f},0f,1064L).all { it==0f })
        assertTrue(display.observe(listOf(Float.NaN,1f,0f,-1f,0f,0f),
            .2f,100L).all { it.isFinite() && it in 0f..1f })
    }
}
