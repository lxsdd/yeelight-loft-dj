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
    val displayBands: List<Float> = bands,
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

    private val tempo = ObservedBeatTempo()
    private val accents = MusicAccentDetector()
    private val spectrumDisplay = AudioSpectrumDisplay()
    private var baselineBass = .025f
    private var lastBass = 0f
    private var lastLowMid = 0f
    private var suppressOneOnset = false

    /**
     * A digital gain change must not erase a valid rhythmic tempo history.
     * Suppress one transitory PCM frame, then resume measuring real onsets.
     */
    @Synchronized
    fun onAnalysisGainChanged() {
        suppressOneOnset = true
        accents.reset()
    }
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
        val monotonic = timeMs >= lastFrameAt
        if (!monotonic || timeMs - lastFrameAt > 5_000L) {
            tempo.reset()
            baselineBass = .025f
            lastBass = 0f
            lastLowMid = 0f
        }
        val lowMid = levels[1] * .65f + levels[2] * .35f
        val bassSlope = bass - lastBass
        val lowMidSlope = lowMid - lastLowMid
        // A real positive spectral rise (bass OR measured low-mid kick) rather
        // than a very high fixed threshold. Sustained tones cannot retrigger.
        val bassAttack = bass > max(.07f, baselineBass * 1.13f) &&
            bassSlope > max(.018f, baselineBass * .16f)
        val lowMidAttack = lowMid > .095f && bass > .05f &&
            lowMidSlope > .038f && bassSlope > .008f
        val meaningful = rms >= .011f && peak >= .04f
        val transient = meaningful && (bassAttack || lowMidAttack)
        val beat = monotonic && !suppressOneOnset && transient &&
            timeMs - lastBeatAt >= 275L
        if (beat) {
            lastBeatAt = timeMs
            tempo.observeOnset(timeMs)
        }
        suppressOneOnset = false
        baselineBass = baselineBass * .93f + bass * .07f
        lastBass = bass
        lastLowMid = lowMid
        lastFrameAt = timeMs
        val measured = MusicFrame(rms, peak, levels, bass, beat, tempo.bpm(timeMs), timeMs)
        return measured.copy(
            accent = accents.observe(measured),
            displayBands = spectrumDisplay.observe(levels,rms,timeMs)
        )
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
