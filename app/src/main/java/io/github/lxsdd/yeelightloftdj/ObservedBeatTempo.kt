package io.github.lxsdd.yeelightloftdj

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Observed-onset tempo only: no guessed BPM from the selected song, no clock
 * generated beats. Allows a missed onset (2x/3x gap) instead of invalidating
 * the complete estimate as RC11 did.
 */
internal class ObservedBeatTempo {
    private val times = ArrayDeque<Long>()
    private var latestTempo: Int? = null
    private var latestEstimateAt: Long? = null

    fun reset() {
        times.clear()
        latestTempo = null
        latestEstimateAt = null
    }

    fun observeOnset(timeMs: Long) {
        val last = times.lastOrNull()
        if (last != null) {
            if (timeMs <= last || timeMs - last > 5_000L) reset()
            else if (timeMs - last < 275L) return
        }
        times.addLast(timeMs)
        while (times.size > 14) times.removeFirst()
        val estimate = estimateFromIntervals()
        if (estimate != null) {
            latestTempo = estimate
            latestEstimateAt = timeMs
        }
    }

    fun bpm(nowMs: Long): Int? {
        val lastBeat = times.lastOrNull() ?: return null
        val estimatedAt = latestEstimateAt ?: return null
        if (nowMs < lastBeat || nowMs - lastBeat > 4_000L ||
            nowMs - estimatedAt > 8_000L) return null
        return latestTempo
    }

    private fun estimateFromIntervals(): Int? {
        if (times.size < 4) return null
        val gaps = times.zipWithNext { a, b -> b - a }
            .filter { it in 275L..3_600L }.takeLast(11)
        if (gaps.size < 3) return null

        data class Fit(
            val period: Double,
            val matched: Int,
            val error: Double,
            val missed: Int,
        )
        val candidates = gaps.flatMap { gap ->
            (1..3).map { multiple -> gap.toDouble() / multiple }
        }.filter { it in 320.0..1_200.0 }.distinct()
        val fits = candidates.map { period ->
            var matched = 0
            var error = 0.0
            var missed = 0
            gaps.forEach { gap ->
                val multiples = (1..3).minBy { n -> abs(gap - n * period) }
                val residual = abs(gap - multiples * period)
                if (residual <= maxOf(70.0, period * .16)) {
                    matched++
                    error += residual / period
                    missed += multiples - 1
                }
            }
            Fit(period, matched, error, missed)
        }.filter { fit ->
            fit.matched >= 3 && fit.matched * 3 >= gaps.size * 2
        }
        val best = fits.maxWithOrNull(
            compareBy<Fit> { it.matched }
                .thenBy { -it.error }
                .thenBy { -it.missed }
        ) ?: return null
        val bpm = (60_000.0 / best.period).roundToInt()
        return bpm.takeIf { it in 50..188 }
    }
}
