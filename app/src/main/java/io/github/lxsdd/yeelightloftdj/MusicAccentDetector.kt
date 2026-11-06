package io.github.lxsdd.yeelightloftdj

/**
 * Distinct from MusicAnalyzer.beat. Identifies *measured* positive rises in
 * one of six FFT-band levels, for diagnostics of kicks, melodic attacks, and
 * upper-frequency events. It deliberately does NOT trigger any lamp writes.
 */
enum class MusicAccent { LOW, MID, HIGH }

internal class MusicAccentDetector {
    private var lastBands: List<Float>? = null
    private var lastTimeMs: Long? = null
    private var lastAccentMs: Long? = null

    fun reset() {
        lastBands = null
        lastTimeMs = null
        lastAccentMs = null
    }

    fun observe(frame: MusicFrame): MusicAccent? {
        val priorTime = lastTimeMs
        if (priorTime != null && frame.timeMs < priorTime) reset()
        lastTimeMs = frame.timeMs
        val prior = lastBands
        lastBands = frame.bands

        if (prior == null || !frame.rms.isFinite() || frame.rms < 0.012f ||
            !frame.peak.isFinite() || frame.peak < 0.045f) return null

        val lastAccent = lastAccentMs
        if (lastAccent != null && frame.timeMs - lastAccent < 220L) return null

        val candidates = frame.bands.indices.mapNotNull { index ->
            val now = frame.bands[index]
            val old = prior[index]
            val rise = now - old
            if (!now.isFinite() || !old.isFinite() || now < 0.10f ||
                rise < maxOf(0.09f, old * 0.55f)) null else index to rise
        }
        val strongest = candidates.maxByOrNull { it.second }?.first ?: return null
        lastAccentMs = frame.timeMs
        return when (strongest) {
            0, 1 -> MusicAccent.LOW
            2, 3 -> MusicAccent.MID
            else -> MusicAccent.HIGH
        }
    }
}
