package io.github.lxsdd.yeelightloftdj

/**
 * Pure visual normalization; does not feed back into beat/accent detection
 * or any LAN/Music-Mode command. Measured levels remain unchanged.
 */
internal class AudioSpectrumDisplay {
    private val runningPeak = FloatArray(6) { .08f }
    private var lastTime: Long? = null

    fun observe(bands: List<Float>, rms: Float, timeMs: Long): List<Float> {
        require(bands.size == 6)
        if (lastTime != null && (timeMs < lastTime!! ||
                timeMs - lastTime!! > 2_000L)) runningPeak.fill(.08f)
        lastTime = timeMs
        if (!rms.isFinite() || rms < .008f) return List(6) { 0f }
        return bands.mapIndexed { index, raw ->
            val clean = if (raw.isFinite()) raw.coerceIn(0f,1f) else 0f
            runningPeak[index] = maxOf(.08f,runningPeak[index] * .985f,clean)
            if (clean < .018f) 0f
            else ((clean - .018f) / (runningPeak[index] - .018f)).coerceIn(0f,1f)
        }
    }
}
