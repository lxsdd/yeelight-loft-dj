package io.github.lxsdd.yeelightloftdj

/**
 * Pure visual normalization; does not feed back into beat/accent detection
 * or any LAN/Music-Mode command. Measured levels remain unchanged.
 */
internal class AudioSpectrumDisplay {
    // Independent, per-band rolling reference. No multiplication flows
    // back into FFT/BPM/accent detection or Color4 transport.
    private val runningPeak = FloatArray(6) { .025f }
    private var lastTime: Long? = null

    fun observe(bands: List<Float>, rms: Float, timeMs: Long): List<Float> {
        require(bands.size == 6)
        if (lastTime != null && (timeMs < lastTime!! ||
                timeMs - lastTime!! > 2_000L)) runningPeak.fill(.025f)
        lastTime = timeMs
        if (!rms.isFinite() || rms < .008f) return List(6) { 0f }
        return bands.mapIndexed { index, raw ->
            val clean = if (raw.isFinite()) raw.coerceIn(0f,1f) else 0f
            runningPeak[index] = maxOf(.025f, runningPeak[index] * .985f, clean)
            // The previous hard .018 floor hid real high-frequency energy.
            // A measured .004..017 should be visible but remains relative
            // display data, NEVER a claimed physical sound level or onset.
            if (clean <= .003f) 0f
            else kotlin.math.sqrt(
                ((clean - .003f) / (runningPeak[index] - .003f)).coerceIn(0f, 1f)
            )
        }
    }
}
