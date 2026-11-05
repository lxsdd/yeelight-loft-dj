package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class Color4LiveVisualDirectorTest {
    private fun frame(rms: Float = .04f, peak: Float = .12f): MusicFrame =
        MusicFrame(rms, peak, listOf(.12f, .23f, .14f, .09f, .07f, .04f),
            .16f, false, 125, 1000L)

    @Test fun fullSpectrumHasDozensOfColorsAndSpreadsFourBulbs() {
        val colors = (0L..38L).map { Color4FullSpectrum.rgb(frame(), 0, 4, it, false) }
        assertTrue("distinct colors = ${colors.distinct().size}", colors.distinct().size >= 22)
        val bulbs = (0..3).map { Color4FullSpectrum.rgb(frame(), it, 4, 2L, true) }
        assertEquals(4, bulbs.distinct().size)
        assertTrue(colors.all { it in 0..0xFFFFFF })
    }

    @Test fun familiarBeatModeOnlyChangesRgbOnEvents() {
        val director = Color4LiveVisualDirector(4, Color4LiveStyle.BEAT)
        assertNull(director.next(frame(), LiveColorPulse.NONE, 0))
        val frames = director.next(frame(), LiveColorPulse.DETECTED_BEAT, 100)!!
        assertEquals(4, frames.size)
        assertEquals(4, frames.map { it.rgb }.distinct().size)
        assertTrue(frames.all { it.transitionMs == 0 && it.brightness == null })
        assertNull(director.next(frame(), LiveColorPulse.NONE, 150))
    }

    @Test fun quietLoudAndQuietAgainProducesRealBrightnessEnvelope() {
        val director = Color4LiveVisualDirector(2, Color4LiveStyle.ADAPTIVE)
        val quiet = director.next(frame(.002f, .01f), LiveColorPulse.NONE, 0)!!
        val low = quiet.first().brightness!!
        var loud = low
        for (time in 1000L..8000L step 1000L) {
            director.next(frame(.10f, .35f), LiveColorPulse.NONE, time)
                ?.first()?.brightness?.let { loud = it }
        }
        assertTrue("quiet $low to loud $loud", loud > low + 30)
        var fade = loud
        for (time in 9000L..25000L step 1000L) {
            director.next(frame(.001f, .005f), LiveColorPulse.NONE, time)
                ?.first()?.brightness?.let { fade = it }
        }
        assertTrue("loud $loud to quiet $fade", fade < loud - 20)
        assertTrue(fade >= 8)
    }

    @Test fun adaptiveFramesUseSmoothRGBAndThrottleBrightness() {
        val director = Color4LiveVisualDirector(4, Color4LiveStyle.ADAPTIVE)
        val first = director.next(frame(), LiveColorPulse.NONE, 0)!!
        assertEquals(4, first.size)
        assertTrue(first.all { it.transitionMs >= 100 && it.brightness in 1..100 })
        assertNull(director.next(frame(), LiveColorPulse.DETECTED_BEAT, 150))
        val second = director.next(frame(), LiveColorPulse.NONE, 950)!!
        assertTrue(second.all { it.brightness == null })
        assertNull(director.next(frame(), LiveColorPulse.NONE, 960))
        assertEquals(4, director.next(frame(), LiveColorPulse.NONE, 2000)!!
            .map { it.rgb }.distinct().size)
    }

    @Test fun gradualEnergyChangesControlSpeedNotOnlyTwoRigidModes() {
        val d = Color4LiveVisualDirector(1, Color4LiveStyle.ADAPTIVE)
        val quiet = d.next(frame(.002f,.01f), LiveColorPulse.NONE, 0)!!.single()
        var middle: Color4MusicVisual? = null
        for (i in 1..12) {
            val shown = d.next(frame(.04f,.13f), LiveColorPulse.NONE, i*900L)
            if (shown != null) middle = shown.single()
        }
        assertNotNull(middle)
        assertTrue("medium should be between slow and fast",
            middle!!.transitionMs in 371..1749)
        var loud: Color4MusicVisual? = null
        for (i in 13..22) {
            d.next(frame(.23f,.65f), LiveColorPulse.DETECTED_BEAT, i*900L)
                ?.let { loud=it.single() }
        }
        assertNotNull(loud)
        assertTrue("loud must be faster than mid",
            loud!!.transitionMs < middle.transitionMs)
        assertTrue(quiet.transitionMs > middle.transitionMs)
    }

    @Test fun adaptiveRgbGetsVisiblyBrighterWithoutOptionalSetBrightTransport() {
        val d = Color4LiveVisualDirector(1, Color4LiveStyle.ADAPTIVE)
        val dim = d.next(frame(.001f,.005f), LiveColorPulse.NONE,0)!!.single()
        var strong: Color4MusicVisual? = null
        repeat(12) { i ->
            d.next(frame(.19f,.60f), LiveColorPulse.NONE,(i+1)*1_000L)
                ?.let { strong=it.single() }
        }
        assertNotNull(strong)
        fun sum(rgb:Int)=((rgb shr 16) and 255)+((rgb shr 8) and 255)+(rgb and 255)
        assertTrue("RGB intensity should increase even if set_bright is ignored",
            sum(strong!!.rgb)>sum(dim.rgb))
    }

    @Test fun missingInvalidOrReversedAudioNeverDispatches() {
        val director = Color4LiveVisualDirector(1, Color4LiveStyle.ADAPTIVE)
        assertNull(director.next(null, LiveColorPulse.NONE, 0))
        assertNull(director.next(frame(Float.NaN), LiveColorPulse.DETECTED_BEAT, 0))
        assertNull(director.next(frame(), LiveColorPulse.NONE, -1))
        assertEquals(1, director.next(frame(), LiveColorPulse.NONE, 0)!!.size)
        assertNull(director.next(frame(), LiveColorPulse.NONE, -2))
        assertEquals(1, Color4LiveVisualDirector(5, Color4LiveStyle.ADAPTIVE)
            .next(frame(), LiveColorPulse.NONE, 0)!!.size / 5)
    }
}
