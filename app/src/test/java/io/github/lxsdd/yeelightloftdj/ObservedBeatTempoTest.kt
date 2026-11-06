package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class ObservedBeatTempoTest {
    @Test fun stable130BpmIsReportedFromFourObservedOnsets() {
        val tempo = ObservedBeatTempo()
        listOf(1000L,1462L,1924L,2386L,2848L).forEach(tempo::observeOnset)
        assertEquals(130, tempo.bpm(2900L))
    }

    @Test fun missingEveryOtherOnsetDoesNotBlankValidBpm() {
        val tempo = ObservedBeatTempo()
        listOf(1000L,1462L,1924L,2848L,3310L,3772L).forEach(tempo::observeOnset)
        assertEquals(130, tempo.bpm(3900L))
        assertNull("No old BPM after four seconds without measured beats",
            tempo.bpm(7900L))
    }

    @Test fun noiseNoReliableSeriesDoesNotInventTempo() {
        val tempo = ObservedBeatTempo()
        listOf(1000L,1462L,2600L).forEach(tempo::observeOnset)
        assertNull(tempo.bpm(2700L))
        tempo.observeOnset(10_000L)
        assertNull(tempo.bpm(10_500L))
    }

    @Test fun actualAnalyzerCountsRegular130BpmPulsesWithoutFictitiousBeats() {
        val analyzer = MusicAnalyzer(16_000,1024)
        val beatsAt = (0 until 18).map {
            (it * 60_000.0 / 130.0 / 64.0).roundToInt()
        }.toSet()
        var counted = 0
        var tempo: Int? = null
        for (i in 0..130) {
            val pulse = i in beatsAt
            val pcm = if (pulse) ShortArray(1024) { sample ->
                (sin(2.0 * PI * 78.125 * sample / 16_000.0) * 22_000.0)
                    .roundToInt().toShort()
            } else ShortArray(1024)
            val reading = analyzer.analyze(pcm,i * 64L)
            if (reading.beat) counted++
            if (reading.estimatedBpm != null) tempo = reading.estimatedBpm
        }
        assertTrue("Most measured 130-BPM kicks should be counted",counted >= 14)
        assertNotNull("BPM must survive ordinary live music frames",tempo)
        assertTrue("Expected real ~130 BPM, got " + tempo, tempo!! in 124..136)
    }

    @Test fun gainChangeDoesNotWipeObservedTempoHistoryOrCreateFakeOnset() {
        val analyzer = MusicAnalyzer(16_000,1024)
        val tone = ShortArray(1024) { i ->
            (sin(2.0 * PI * 78.125 * i / 16_000.0) * 22_000.0).toInt().toShort()
        }
        val silent = ShortArray(1024)
        for (i in 0..5) {
            analyzer.analyze(silent, 1000L+i*500)
            analyzer.analyze(tone, 1100L+i*500)
        }
        val before = analyzer.analyze(silent, 3800L).estimatedBpm
        assertNotNull(before)
        analyzer.onAnalysisGainChanged()
        val gainFrame = analyzer.analyze(tone,3850L)
        assertFalse("Gain step must not invent a beat",gainFrame.beat)
        assertNotNull("Previously measured BPM must not be erased",gainFrame.estimatedBpm)
    }
}
