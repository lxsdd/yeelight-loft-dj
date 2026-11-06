package io.github.lxsdd.yeelightloftdj

import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Pure presentation of measured PCM digital amplitude. dBFS is relative to
 * full-scale PCM; it is NOT acoustic SPL, phone microphone calibration, gain,
 * or a fabricated percentage of how loud the room is.
 *
 * Display progress maps -60 dBFS (quiet) to -6 dBFS (strong signal) on a
 * logarithmic scale, but neither analyzed frames nor lamp commands change.
 */
internal data class AudioLevelReading(
    val rmsDbfs: Float?,
    val peakDbfs: Float?,
    val displayLevel: Float,
) {
    val rmsLabel: String get() = AudioLevelDisplay.label(rmsDbfs)
    val peakLabel: String get() = AudioLevelDisplay.label(peakDbfs)
}

internal object AudioLevelDisplay {
    private const val FLOOR_DBFS = -60f
    private const val HIGH_DBFS = -6f

    fun dbfs(amplitude: Float): Float? {
        if (!amplitude.isFinite()) return null
        if (amplitude <= 0f) return Float.NEGATIVE_INFINITY
        return (20.0 * log10(amplitude.coerceAtMost(1f).toDouble())).toFloat()
    }

    fun from(rms: Float, peak: Float): AudioLevelReading {
        val rmsDb = dbfs(rms)
        val peakDb = dbfs(peak)
        val meter = if (rmsDb == null || rmsDb == Float.NEGATIVE_INFINITY) 0f else
            ((rmsDb - FLOOR_DBFS) / (HIGH_DBFS - FLOOR_DBFS)).coerceIn(0f, 1f)
        return AudioLevelReading(rmsDb, peakDb, meter)
    }

    fun label(dbfs: Float?): String = when {
        dbfs == null -> "–"
        dbfs == Float.NEGATIVE_INFINITY -> "−∞ dBFS"
        else -> "${dbfs.roundToInt()} dBFS"
    }
}
