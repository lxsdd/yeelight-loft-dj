package loftdj.classic

import kotlin.math.abs
import kotlin.math.max

/** Pure DSP post-processor. Audio acquisition and lamp output are deliberately separate. */
class ClassicBeatAnalyzer(
    private val minBpm: Double = 75.0,
    private val maxBpm: Double = 180.0,
    private val refractoryMs: Long = 260L
) {
    init { require(minBpm > 0 && maxBpm > minBpm && refractoryMs > 0) }
    data class Result(val beat: Boolean, val bpm: Double?, val bass: Double,
                      val mid: Double, val presence: Double, val treble: Double,
                      val confidence: Double)
    private val history = java.util.ArrayDeque<Long>()
    private var lastTimestamp = -1L
    private var lastBeat = -1L
    private var baseline = 0.0
    private var previousFlux = 0.0
    private var previousWasHigh = false
    private var currentBpm: Double? = null
    private var confidence = 0.0

    /**
     * Bands contain nonnegative, finite spectral energy (not dB); caller maps
     * bins to bands and owns its microphone/MediaProjection permission lifecycle.
     * A backwards timestamp resets the estimator instead of recycling old beats.
     */
    @Synchronized fun accept(timestampMs: Long, bands: DoubleArray, sensitivity: Double = 1.0): Result {
        require(timestampMs >= 0 && bands.size == 4 && bands.all { it.isFinite() && it >= 0.0 })
        require(sensitivity.isFinite() && sensitivity in 0.1..10.0)
        if (lastTimestamp >= 0 && timestampMs <= lastTimestamp) {
            reset()
        }
        lastTimestamp = timestampMs
        val normalized = DoubleArray(4) { i -> (bands[i] * sensitivity).coerceAtMost(1.0) }
        // Bass-heavy onset energy; slow adaptive floor prevents constant bass from
        // triggering repeated beats. Beat detection is a rising-edge event.
        val flux = (normalized[0] * 0.65 + normalized[1] * 0.25 + normalized[2] * 0.10)
        baseline = if (baseline == 0.0) flux * 0.25 else baseline * 0.96 + flux * 0.04
        val high = flux > max(0.09, baseline * 1.6) && flux > previousFlux * 1.12
        val beat = high && !previousWasHigh && (lastBeat < 0 || timestampMs - lastBeat >= refractoryMs)
        previousWasHigh = high
        previousFlux = flux
        if (beat) {
            if (lastBeat >= 0) {
                val interval = timestampMs - lastBeat
                val candidate = 60000.0 / interval
                if (candidate in minBpm..maxBpm) {
                    history.addLast(interval)
                    while (history.size > 8) history.removeFirst()
                    val sorted = history.sorted()
                    val median = sorted[sorted.size / 2].toDouble()
                    val consistent = history.count { abs(it - median) <= median * 0.14 }
                    if (consistent >= 2) {
                        currentBpm = 60000.0 / median
                        confidence = consistent.toDouble() / history.size
                    }
                } else {
                    history.clear(); currentBpm = null; confidence = 0.0
                }
            }
            lastBeat = timestampMs
        }
        if (lastBeat >= 0 && timestampMs - lastBeat > 2400) {
            history.clear(); currentBpm = null; confidence = 0.0
        }
        return Result(beat, currentBpm, normalized[0], normalized[1], normalized[2], normalized[3], confidence)
    }
    @Synchronized fun reset() {
        history.clear(); lastTimestamp = -1; lastBeat = -1
        baseline = 0.0; previousFlux = 0.0; previousWasHigh = false
        currentBpm = null; confidence = 0.0
    }
}
