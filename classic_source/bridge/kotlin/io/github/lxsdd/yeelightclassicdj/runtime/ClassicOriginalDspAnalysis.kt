package io.github.lxsdd.yeelightclassicdj.runtime

import io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalMusicAudioSignal
import loftdj.classic.ClassicBeatAnalyzer
import loftdj.classic.ClassicPcmBands
import kotlin.math.sqrt

/**
 * Genuine original Classic mic/FFT -> existing Loft DJ FFT and BeatAnalyzer.
 * Read-only, with no AudioRecord/Visualizer/MediaProjection or lamp ownership.
 */
class ClassicOriginalDspAnalysis : ClassicOriginalMusicAudioSignal.Analyzer {
    private var source = ""
    private var lastTime = -1L
    private var rateHz = 0
    private var pcmBands: ClassicPcmBands? = null
    private val beats = ClassicBeatAnalyzer()
    private var gain = 1.0

    private var lastPcmAtMs = -1L
    // Short, in-memory rolling window only. Original PCM is never persisted.
    private val pcmHistory = ShortArray(1024)
    private var historyWrite = 0
    private var historyCount = 0
    private val maxWindowsPerRead = 8
    private val fftFallbackMs = 500L

    private fun resetPcmWindow() {
        pcmHistory.fill(0)
        historyWrite = 0
        historyCount = 0
    }

    /**
     * The OEM may emit mic and Visualizer callbacks in overlapping workers.
     * Prefer genuine PCM while it is fresh, and reject delayed/duplicate
     * callbacks instead of corrupting the shared tempo estimator.
     */
    @Synchronized private fun sourceAt(kind: String, hz: Int, at: Long): Boolean {
        if (lastTime >= 0L && at <= lastTime) return false
        if (kind == "FFT" && lastPcmAtMs >= 0L &&
            at - lastPcmAtMs <= fftFallbackMs) return false
        if (kind != source || hz != rateHz ||
            (lastTime >= 0L && at - lastTime > 1250L)) {
            beats.reset()
            gain = 1.0
            pcmBands = if (kind == "MIC") ClassicPcmBands(hz, 1024) else null
            resetPcmWindow()
        }
        source = kind
        rateHz = hz
        lastTime = at
        if (kind == "MIC") lastPcmAtMs = at
        return true
    }

    private fun spectrumWindow(): DoubleArray {
        // Reassemble 1024 consecutive ORIGINAL samples, including reads that
        // arrived in 128/256/512 sample pieces; never zero-pad a partial read.
        val ordered = ShortArray(pcmHistory.size) { i ->
            pcmHistory[(historyWrite + i) % pcmHistory.size]
        }
        return checkNotNull(pcmBands).bands(ordered)
    }

    @Synchronized override fun onPcm(
        pcm: ShortArray, valid: Int, rateHz: Int, timestampMs: Long
    ): ClassicOriginalMusicAudioSignal.Frame? {
        if (valid !in 1..minOf(pcm.size, 131072) ||
            rateHz !in 8000..192000 || timestampMs < 0L) return null
        if (!sourceAt("MIC", rateHz, timestampMs)) return null
        // Preserve split OEM reads, and measure the strongest (bounded)
        // spectral window from a large read instead of losing early kicks.
        // One BeatAnalyzer update per REAL callback timestamp: no invented
        // capture clocks and no additional AudioRecord/Visualizer.
        val bands = DoubleArray(4)
        val stride = maxOf(1024, (valid + maxWindowsPerRead - 1) / maxWindowsPerRead)
        for (i in 0 until valid) {
            pcmHistory[historyWrite] = pcm[i]
            historyWrite = (historyWrite + 1) % pcmHistory.size
            if (historyCount < pcmHistory.size) historyCount++
            if (historyCount == pcmHistory.size &&
                ((i + 1) % stride == 0 || i == valid - 1)) {
                val window = spectrumWindow()
                for (band in bands.indices) {
                    if (window[band] > bands[band]) bands[band] = window[band]
                }
            }
        }
        var squares = 0.0
        for (i in 0 until valid) {
            val value = pcm[i].toDouble() / 32768.0
            squares += value * value
        }
        val rms = sqrt(squares / valid)
        val target = if (rms < 0.0015) 1.0 else (0.12 / rms).coerceIn(1.0, 8.0)
        gain += (target - gain) * if (target > gain) 0.22 else 0.08
        val result = beats.accept(timestampMs, bands, gain.coerceIn(1.0, 8.0))
        return ClassicOriginalMusicAudioSignal.Frame(
            sqrt(rms).toFloat().coerceIn(0f, 1f),
            floatArrayOf(result.bass.toFloat(), result.mid.toFloat(),
                         result.presence.toFloat(), result.treble.toFloat()),
            result.beat, (result.bpm ?: 0.0).toFloat(), result.confidence.toFloat()
        )
    }

    @Synchronized override fun onFft(
        fft: ByteArray, samplingMilliHz: Int, timestampMs: Long
    ): ClassicOriginalMusicAudioSignal.Frame? {
        if (fft.size !in 16..32768 || fft.size % 2 != 0 ||
            samplingMilliHz !in 8000000..192000000 || timestampMs < 0L) return null
        val hz = samplingMilliHz / 1000
        if (!sourceAt("FFT", hz, timestampMs)) return null
        // Android's 8-bit complex Visualizer FFT is not raw full-resolution PCM.
        val power = DoubleArray(4)
        val counts = IntArray(4)
        for (bin in 1 until fft.size / 2) {
            val frequency = bin.toDouble() * hz / fft.size
            if (frequency < 20.0 || frequency >= hz / 2.0) continue
            val band = when {
                frequency < 160.0 -> 0
                frequency < 800.0 -> 1
                frequency < 4000.0 -> 2
                else -> 3
            }
            val re = fft[bin * 2].toInt()
            val im = fft[bin * 2 + 1].toInt()
            power[band] += sqrt((re * re + im * im).toDouble()) / 181.0
            counts[band]++
        }
        val bands = DoubleArray(4) { i ->
            if (counts[i] == 0) 0.0 else
                sqrt(power[i] / counts[i]).coerceIn(0.0, 1.0)
        }
        val result = beats.accept(timestampMs, bands)
        val level = bands.maxOrNull() ?: 0.0
        return ClassicOriginalMusicAudioSignal.Frame(
            level.toFloat(),
            floatArrayOf(result.bass.toFloat(), result.mid.toFloat(),
                         result.presence.toFloat(), result.treble.toFloat()),
            result.beat, (result.bpm ?: 0.0).toFloat(), result.confidence.toFloat()
        )
    }
}
