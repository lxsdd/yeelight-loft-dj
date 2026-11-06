package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputDiagnosticsTest {
    @Test fun inputSourceRestorationIsConservativeAndUnprocessedIsConditional() {
        assertEquals(MicrophoneInputProfile.MIC, MicrophoneInputProfile.restore(null))
        assertEquals(MicrophoneInputProfile.MIC, MicrophoneInputProfile.restore("UNKNOWN"))
        assertEquals(MicrophoneInputProfile.VOICE_RECOGNITION,
            MicrophoneInputProfile.restore("VOICE_RECOGNITION"))
        assertTrue(MicrophoneInputProfile.UNPROCESSED in MicrophoneInputProfile.available(true))
        assertFalse(MicrophoneInputProfile.UNPROCESSED in MicrophoneInputProfile.available(false))
        assertTrue(MicrophoneInputProfile.VOICE_COMMUNICATION in MicrophoneInputProfile.available(false))
    }

    @Test fun rawMeterUsesUnamplifiedSamplesAndClippingIsSeparatelyCounted() {
        val raw = shortArrayOf(1000, -1000, 1000, -1000)
        val level = RawMicLevel.from(raw)
        assertEquals(1000f / 32768f, level.rms, .00001f)
        assertEquals(1000f / 32768f, level.peak, .00001f)
        assertEquals(0, level.clipped)
        assertEquals(0, RawMicLevel.from(raw).clipped)
        val boosted = MicrophoneAnalysisGain.apply(raw, 4f)
        assertTrue(RawMicLevel.from(boosted).rms > level.rms)
        assertEquals(0, level.clipped)
        val clipping = RawMicLevel.from(shortArrayOf(32767, -32768, 0))
        assertEquals(2, clipping.clipped)
    }

    @Test fun silenceDoesNotCauseAutomaticGainOrFabricatedSignal() {
        val auto = AdaptiveMicrophoneGain()
        val silent = RawMicLevel.from(ShortArray(1024))
        repeat(15) { assertEquals(1f, auto.observe(silent, it * 200L), 0f) }
        assertEquals(-120f, silent.rmsDbfs(), 0f)
        assertEquals(-120f, silent.peakDbfs(), 0f)
    }

    @Test fun weakButAudibleSoundGetsBoundedBoostForAnalysis() {
        val auto = AdaptiveMicrophoneGain()
        val weak = RawMicLevel.from(ShortArray(1024) { if (it % 2 == 0) 500 else -500 })
        val gain = auto.observe(weak, 1000L)
        assertEquals(4f, gain, 0f)
        assertEquals(gain, auto.observe(weak, 1100L), 0f)
        val processed = MicrophoneAnalysisGain.apply(
            ShortArray(1024) { 500 }, gain)
        assertEquals(2000, processed[0].toInt())
        assertEquals(0, RawMicLevel.from(processed).clipped)
    }

    @Test fun loudAudioIsPeakLimitedAndCannotExceedHardwareFullScale() {
        val auto = AdaptiveMicrophoneGain()
        val weak = RawMicLevel.from(ShortArray(1024) { 450 })
        assertEquals(4f, auto.observe(weak, 1000L), 0f)
        val loud = RawMicLevel.from(ShortArray(1024) { 30000 })
        val now = auto.observe(loud, 1200L)
        assertTrue(now <= 1f)
        assertTrue(loud.peak * now <= .92f)
        assertTrue(now >= .5f && now <= 4f)
    }

    @Test fun tinyNoiseCannotActivateAutoGain() {
        val auto = AdaptiveMicrophoneGain()
        val noise = RawMicLevel.from(ShortArray(1024) { if (it % 2 == 0) 20 else -20 })
        assertEquals(1f, auto.observe(noise, 0L), 0f)
        assertEquals(1f, auto.observe(noise, 10_000L), 0f)
    }

    @Test fun gainHistoryHysteresisPreventsFrequentTransientReset() {
        val auto = AdaptiveMicrophoneGain()
        val moderate = RawMicLevel.from(ShortArray(1024) { 1400 })
        assertTrue(auto.observe(moderate, 1000L) >= 1.25f)
        val original = auto.gain
        val near = RawMicLevel.from(ShortArray(1024) { 1500 })
        assertEquals(original, auto.observe(near, 1200L), 0f)
    }
}
