package loftdj.classic

import android.media.audiofx.Visualizer
import kotlin.math.PI
import kotlin.math.sin

fun main() {
    Visualizer.reset()
    check(runCatching { ClassicAndroidVisualizerInput.open(-1) }.isFailure)
    check(Visualizer.constructed == 0)
    check(runCatching { ClassicAndroidVisualizerInput.open(7, 48000) }.isFailure)
    check(Visualizer.constructed == 0)

    val input = ClassicAndroidVisualizerInput.open(27)
    val visualizer = Visualizer.last!!
    check(visualizer.sessionId == 27 && visualizer.captureSize == 1024)
    check(visualizer.enabled)
    // A real captured silent unsigned waveform must be zero-centered, not
    // interpreted as a loud negative signed 8-bit signal.
    visualizer.emit(ByteArray(1024) { 128.toByte() }, 16_000_000)
    val silence = ShortArray(1024)
    check(input.read(silence) == 1024 && silence.all { it == 0.toShort() })
    check(input.read(ShortArray(1024)) == 0)

    val tone = ByteArray(1024) { i ->
        (128.0 + 70.0 * sin(2.0 * PI * 90.0 * i / 16000.0)).toInt().toByte()
    }
    visualizer.emit(tone, 16_000_000)
    val samples = ShortArray(1024)
    check(input.read(samples) == 1024)
    check(samples.any { it != 0.toShort() })
    // The existing production FFT receives actual converted Visualizer samples.
    val measured = ClassicPcmBands(16000, 1024).bands(samples)
    check(measured[0] == measured.maxOrNull() && measured[0] > 0.0)

    // The original mix may run at 48 kHz. The input must resample to the
    // identical 16-kHz PCM contract, never re-label 48-kHz frames as 16 kHz.
    repeat(4) { visualizer.emit(tone, 48_000_000) }
    check(input.read(ShortArray(1024)) == 1024)
    check(input.read(ShortArray(1024)) < 512)
    visualizer.emit(ByteArray(1024) { 128.toByte() }, 0) // invalid rate ignored
    check(input.read(ShortArray(1024)) == 0)

    // Overflow evicts old waveform observations and never grows the queue.
    repeat(20) { visualizer.emit(tone, 16_000_000) }
    check(input.read(ShortArray(9000)) == 8192)
    val retained = visualizer.listener!!
    input.close(); input.close()
    check(Visualizer.released == 1 && visualizer.releasedOnce && !visualizer.enabled)
    retained.onWaveFormDataCapture(visualizer, tone, 16_000_000)
    check(runCatching { input.read(ShortArray(32)) }.isFailure)

    Visualizer.reset()
    Visualizer.rejectedConstructors = true
    check(runCatching { ClassicAndroidVisualizerInput.open(0) }.exceptionOrNull() is SecurityException)
    check(Visualizer.constructed == 0)
    Visualizer.reset()
    Visualizer.rejectedSizes = true
    check(runCatching { ClassicAndroidVisualizerInput.open(0) }.isFailure)
    check(Visualizer.released == 1)
    Visualizer.reset()
    Visualizer.rejectedListeners = true
    check(runCatching { ClassicAndroidVisualizerInput.open(0) }.isFailure)
    check(Visualizer.released == 1)
    Visualizer.reset()
    Visualizer.rejectedEnabling = true
    check(runCatching { ClassicAndroidVisualizerInput.open(0) }.isFailure)
    check(Visualizer.released == 1)
    Visualizer.reset()
    println("PASS Visualizer original-session capture 8-bit wave, real FFT, 16/48-kHz conversion, bounded queue, denied permission, lifecycle")
}
