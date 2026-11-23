package io.github.lxsdd.yeelightclassicdj.runtime

import kotlin.math.PI
import kotlin.math.sin

private fun sine(hz: Double, rate: Int = 16000, amp: Double = 22000.0) =
    ShortArray(1024) { i ->
        (sin(2.0 * PI * hz * i / rate) * amp).toInt().toShort()
    }

fun main() {
    listOf(90.0, 400.0, 1700.0, 5200.0).forEachIndexed { expected, hz ->
        val dsp = ClassicOriginalDspAnalysis()
        val frame = checkNotNull(dsp.onPcm(sine(hz), 1024, 16000, 100))
        check(frame.bands.all { it.isFinite() && it in 0f..1f })
        check(frame.bands.indices.maxByOrNull { frame.bands[it] } == expected) {
            "Original 16 kHz PCM band frequency " + hz + " -> " + frame.bands.toList()
        }
    }
    val dsp = ClassicOriginalDspAnalysis()
    val silence = checkNotNull(dsp.onPcm(ShortArray(1024), 1024, 16000, 50))
    check(silence.bands.all { it == 0f } && !silence.beat && silence.bpm == 0f)
    var beats = 0
    var last = silence
    for (i in 0..6) {
        last = checkNotNull(dsp.onPcm(
            if (i % 2 == 0) sine(90.0) else ShortArray(1024),
            1024, 16000, 100L + i * 250L))
        if (last.beat) beats++
    }
    check(beats >= 2) { "Shared onset detector missed 120 BPM kick pulses" }
    check(last.bpm in 115f..125f && last.confidence in 0f..1f) {
        "Existing shared BPM did not converge " + last.bpm
    }
    val changedRate = checkNotNull(dsp.onPcm(sine(400.0,48000),1024,48000,2100))
    check(changedRate.bpm == 0f) { "Sample-rate change leaked old BPM" }
    check(dsp.onPcm(sine(90.0), 1025, 16000, 2101) == null)
    check(dsp.onPcm(sine(90.0), 1024, 0, 2101) == null)
    val fft = ByteArray(512)
    fft[2] = 90; fft[3] = 90 // 44.1 kHz, 86 Hz original Android complex bin
    val read = checkNotNull(dsp.onFft(fft, 44100000, 2200))
    check(read.bands[0] > read.bands[1] && read.bands[0] > 0f)
    check(read.bpm == 0f) { "Different OEM audio source leaked old BPM" }
    check(dsp.onFft(fft, 0, 2300) == null)
    check(dsp.onFft(ByteArray(17), 44100000, 2300) == null)
    println("PASS: original OEM callbacks -> shared real PCM FFT, AGC, beat, BPM; source isolation")
}
