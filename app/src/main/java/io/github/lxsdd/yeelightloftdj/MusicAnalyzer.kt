package io.github.lxsdd.yeelightloftdj

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Measured PCM only. Nothing here fabricates a waveform, beat or BPM. */
data class MusicFrame(
    val rms: Float,
    val peak: Float,
    val bands: List<Float>,
    val bass: Float,
    val beat: Boolean,
    val estimatedBpm: Int?,
    val timeMs: Long,
    // A measured spectral-band rise, distinct from a detected bass beat.
    // It is for on-screen diagnostics; it does not produce any lamp command.
    val accent: MusicAccent? = null,
) {
    init { require(bands.size == 6) }
}

/**
 * Radix-2 real PCM FFT + six frequency bands + adaptive bass transient detector.
 * Pure Kotlin, deterministic JVM tests; no third-party network service or DSP library.
 */
class MusicAnalyzer(private val sampleRate: Int = 16000, val fftSize: Int = 2048) {
    init {
        require(sampleRate in 8000..48000)
        require(fftSize in 512..8192 && fftSize.countOneBits() == 1)
    }

    private val history = ArrayDeque<Long>()
    private val accents = MusicAccentDetector()
    private var baselineBass = 0.025f
    private var lastBass = 0f
    private var lastBeatAt = Long.MIN_VALUE / 2
    private var lastFrameAt = Long.MIN_VALUE / 2
    private val bounds = intArrayOf(35, 120, 250, 600, 1400, 3200, 7800)

    @Synchronized
    fun analyze(pcm: ShortArray, timeMs: Long): MusicFrame {
        require(pcm.size == fftSize)
        // Windowing prevents abrupt FFT-bin boundary artifacts.
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var squares = 0.0
        var peak = 0f
        pcm.forEachIndexed { i, sample ->
            val value = sample.toDouble() / 32768.0
            squares += value * value
            peak = max(peak, kotlin.math.abs(value).toFloat())
            val window = 0.5 - 0.5 * cos(2 * PI * i / (fftSize - 1))
            re[i] = value * window
        }
        fft(re, im)
        val raw = FloatArray(6)
        val counts = IntArray(6)
        for (bin in 1 until fftSize / 2) {
            val hz = bin.toDouble() * sampleRate / fftSize
            val band = (0 until 6).firstOrNull { hz >= bounds[it] && hz < bounds[it + 1] } ?: continue
            val mag = hypot(re[bin], im[bin]) * 4.0 / fftSize
            raw[band] += (mag * mag).toFloat()
            counts[band]++
        }
        val levels = raw.indices.map { i ->
            // 0..1 is a display normalization only, not a fabricated physical value.
            min(1f, (sqrt(raw[i] / max(1, counts[i])) * 9f))
        }
        val bass = levels[0] * 0.7f + levels[1] * 0.3f
        val rms = sqrt(squares / fftSize).toFloat()
        val meaningful = rms >= 0.016f && bass >= 0.075f
        val transient = meaningful &&
            bass > max(0.115f, baselineBass * 1.65f) &&
            bass > lastBass * 1.16f
        val monotonic = timeMs >= lastFrameAt
        val beat = monotonic && transient && timeMs - lastBeatAt >= 300L
        if (!monotonic || timeMs - lastFrameAt > 5000L) history.clear()
        if (beat) {
            lastBeatAt = timeMs
            history.addLast(timeMs)
            while (history.size > 8) history.removeFirst()
        }
        if (timeMs - lastBeatAt > 4000L) history.clear()
        baselineBass = baselineBass * 0.90f + bass * 0.10f
        lastBass = bass
        lastFrameAt = timeMs
        val measured = MusicFrame(rms, peak, levels, bass, beat, bpm(), timeMs)
        return measured.copy(accent = accents.observe(measured))
    }

    private fun bpm(): Int? {
        if (history.size < 4) return null
        val gaps = history.zipWithNext { a, b -> b - a }
            .filter { it in 320L..1300L }
        if (gaps.size < 3 || gaps.size != history.size - 1) return null
        val median = gaps.sorted()[gaps.size / 2].toDouble()
        if (gaps.any { kotlin.math.abs(it - median) > median * 0.17 }) return null
        return (60000.0 / median).roundToInt().takeIf { it in 45..188 }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val ii = im[i]; im[i] = im[j]; im[j] = ii
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wlenRe = cos(angle)
            val wlenIm = sin(angle)
            var start = 0
            while (start < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val even = start + k
                    val odd = even + len / 2
                    val tr = wr * re[odd] - wi * im[odd]
                    val ti = wr * im[odd] + wi * re[odd]
                    re[odd] = re[even] - tr
                    im[odd] = im[even] - ti
                    re[even] += tr
                    im[even] += ti
                    val newWr = wr * wlenRe - wi * wlenIm
                    wi = wr * wlenIm + wi * wlenRe
                    wr = newWr
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/** One color command maximum per bulb per 1.5 s (40/min < Yeelight 60/connection). */
object MusicLightPolicy {
    const val minCommandIntervalMs = 1500L

    fun maySend(
        frame: MusicFrame, previousSentAt: Long, nowMs: Long,
        minimumIntervalMs: Long = minCommandIntervalMs,
    ): Boolean =
        frame.beat && frame.rms >= 0.016f && minimumIntervalMs >= 250L &&
            nowMs >= previousSentAt && nowMs - previousSentAt >= minimumIntervalMs

    fun color(frame: MusicFrame, lampIndex: Int, beatNumber: Long): Int {
        val palettes = listOf(
            listOf(0xE55AFF, 0x5048FF, 0x18BACC),
            listOf(0xFF7F50, 0xFF4DAC, 0xAF54FF),
            listOf(0x39DBD0, 0x3578FF, 0xC16BFF)
        )
        val dominant = frame.bands.indices.maxByOrNull { frame.bands[it] } ?: 0
        val palette = palettes[when {
            dominant <= 1 -> 0
            dominant <= 3 -> 1
            else -> 2
        }]
        return palette[Math.floorMod(beatNumber + lampIndex.toLong(), palette.size.toLong()).toInt()]
    }
}
