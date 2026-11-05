package io.github.lxsdd.yeelightloftdj

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OverlappingPcmWindowsTest {
    @Test fun exactHalfHopCreatesNoInventedOrDuplicatedSamples() {
        val overlap=OverlappingPcmWindows(1024)
        assertNull(overlap.accept(ShortArray(512) { 1 }))
        val first=overlap.accept(ShortArray(512) { 2 })!!
        assertTrue(first.take(512).all { it == 1.toShort() })
        assertTrue(first.drop(512).all { it == 2.toShort() })
        val second=overlap.accept(ShortArray(512) { 3 })!!
        assertTrue(second.take(512).all { it == 2.toShort() })
        assertTrue(second.drop(512).all { it == 3.toShort() })
        assertEquals(1.toShort(),first[0]) // copies must not mutate
    }

    @Test fun measured130BpmBassPulseTrainCanBeSeenWith32MsHop() {
        val sampleRate=16_000
        val sampleCount=sampleRate*6
        val samples=ShortArray(sampleCount) { i ->
            // Every 461.5 ms a 95 ms 78 Hz low-frequency kick, separated by
            // measured silence. This is a PCM pulse train, not fake BPM input.
            val inBeat=i % 7_384
            if(inBeat < 1_520) {
                (sin(2*PI*78.125*(inBeat.toDouble()/sampleRate))*21_000).toInt().toShort()
            } else 0
        }
        val overlap=OverlappingPcmWindows(1024)
        val detector=MusicAnalyzer(16000,1024)
        val events=mutableListOf<Long>()
        var end=0
        while(end+512<=sampleCount) {
            val chunk=samples.copyOfRange(end,end+512)
            end+=512
            val frame=overlap.accept(chunk) ?: continue
            val timestamp=(end*1000L)/sampleRate
            if(detector.analyze(frame,timestamp).beat) events+=timestamp
        }
        assertTrue("Expected multiple measured 130 BPM kicks: $events",events.size>=8)
        assertTrue("Need 32ms onset timing, not false rapid repeats: $events",
            events.zipWithNext().all { (a,b) -> b-a>=275L })
        val check=detector.analyze(ShortArray(1024),6_020L)
        assertTrue("Estimated BPM should come from measured kicks: ${check.estimatedBpm}",
            check.estimatedBpm in 122..138)
    }
}
