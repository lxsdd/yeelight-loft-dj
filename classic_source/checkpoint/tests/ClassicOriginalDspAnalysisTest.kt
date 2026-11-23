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
    check(dsp.onFft(fft, 44100000, 2200) == null) {
        "Concurrent OEM Visualizer must not reset a fresh original PCM tempo"
    }
    val read = checkNotNull(dsp.onFft(fft, 44100000, 2700))
    check(read.bands[0] > read.bands[1] && read.bands[0] > 0f)
    check(read.bpm == 0f) { "Different OEM audio source leaked old BPM" }
    check(dsp.onFft(fft, 0, 2300) == null)
    check(dsp.onFft(ByteArray(17), 44100000, 2300) == null)

    // These execute the real DSP source, not a duplicated test helper.
    // A 1024-sample sine arriving in four 256-sample OEM reads must be
    // reconstructed before FFT; early incomplete chunks cannot invent beats.
    val complete = sine(90.0)
    val split = ClassicOriginalDspAnalysis()
    for (i in 0..2) {
        val partial = checkNotNull(split.onPcm(
            complete.copyOfRange(i * 256, (i + 1) * 256), 256, 16000,
            100L + i * 20L))
        check(!partial.beat && partial.bands.all { it == 0f }) {
            "Incomplete original PCM read was analyzed as zero-padded audio"
        }
    }
    val reconstructed = checkNotNull(split.onPcm(
        complete.copyOfRange(768, 1024), 256, 16000, 160))
    check(reconstructed.bands[0] > reconstructed.bands[1] &&
          reconstructed.bands[0] > 0f) { "Split original PCM lost its bass" }
    check(split.onPcm(sine(400.0), 1024, 16000, 159) == null) {
        "Out-of-order callback must not damage current beat state"
    }
    check(split.onPcm(sine(400.0), 1024, 16000, 160) == null) {
        "Duplicate timestamp must not reset the estimator"
    }

    val transient = ClassicOriginalDspAnalysis()
    val longOriginalRead = ShortArray(4096)
    val startKick = sine(90.0)
    System.arraycopy(startKick, 0, longOriginalRead, 0, 1024)
    val observed = checkNotNull(transient.onPcm(longOriginalRead, 4096, 16000, 100))
    check(observed.bands[0] > 0f) {
        "Original capture read lost an early kick in its trailing silent window"
    }
    val overlapping = ClassicOriginalDspAnalysis()
    checkNotNull(overlapping.onPcm(sine(90.0), 1024, 16000, 100))
    check(overlapping.onFft(fft, 16000000, 150) == null) {
        "Original Visualizer interleaving must not oscillate PCM/FFT DSP owners"
    }
    checkNotNull(overlapping.onPcm(sine(90.0), 1024, 16000, 200))
    checkNotNull(overlapping.onFft(fft, 16000000, 701)) // real fallback gap
    val changed = checkNotNull(overlapping.onPcm(sine(400.0, 48000),
        1024, 48000, 750))
    check(changed.bpm == 0f) { "Source switch retained stale tempo" }

    val cleanRestart = ClassicOriginalDspAnalysis()
    checkNotNull(cleanRestart.onPcm(sine(90.0), 1024, 16000, 100))
    cleanRestart.onCaptureClosed()  // real original AudioRecord stop / host revoke
    val afterStop = checkNotNull(cleanRestart.onPcm(ShortArray(256), 256, 16000, 120))
    check(afterStop.bands.all { it == 0f } && afterStop.bpm == 0f && !afterStop.beat) {
        "New short microphone session inherited the old PCM window or tempo"
    }
    cleanRestart.onCaptureClosed()
    checkNotNull(cleanRestart.onFft(fft, 44100000, 130)).also {
        check(it.bpm == 0f) { "Stopped mic blocked independent OEM FFT fallback" }
    }
    cleanRestart.onCaptureClosed() // repeated release is harmless
    val cleanAgain = checkNotNull(cleanRestart.onPcm(ShortArray(256), 256, 16000, 140))
    check(cleanAgain.bands.all { it == 0f } && cleanAgain.bpm == 0f) {
        "Repeated OEM release leaked DSP history into a new source"
    }

    println("PASS: original OEM callbacks -> shared real PCM FFT, AGC, beat, BPM; source isolation")
}
