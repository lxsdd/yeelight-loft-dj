package loftdj.classic

/** Explicit PCM input bridge; caller owns AudioRecord lifecycle and permission. */
class ClassicPcmDjBridge(
    private val engine: ClassicDjEngine,
    sampleRate: Int = 16000,
    windowSize: Int = 1024,
    private val automaticAnalysisGain: Boolean = false
) {
    private val spectral = ClassicPcmBands(sampleRate, windowSize)
    private var adaptiveGain = 1.0
    private fun measuredGain(pcm: ShortArray): Double {
        val energy = pcm.fold(0.0) { acc, sample ->
            val normalized = sample.toDouble() / 32768.0
            acc + normalized * normalized
        }
        val rms = kotlin.math.sqrt(energy / pcm.size)
        val target = if (rms < 0.012) 1.0 else (0.16 / rms).coerceIn(1.0, 8.0)
        adaptiveGain = (adaptiveGain * 0.88 + target * 0.12).coerceIn(1.0, 8.0)
        return adaptiveGain
    }
    @Synchronized fun accept(timestampMs: Long, pcm: ShortArray, gain: Double = 1.0): ClassicDjEngine.Output {
        // Compute and validate before dispatch; invalid PCM must never cause lamp output.
        require(gain.isFinite() && gain in 0.1..10.0)
        val corrected = if (automaticAnalysisGain) (measuredGain(pcm) * gain).coerceIn(0.1, 10.0) else gain
        return engine.audio(timestampMs, spectral.bands(pcm), corrected)
    }
}
