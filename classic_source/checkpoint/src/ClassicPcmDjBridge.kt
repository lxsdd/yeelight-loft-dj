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
    private var liveSensitivity = 1.0

    /** Thread-safe setting: never restarts AudioRecord or changes Music ownership. */
    @Synchronized fun setSensitivity(value: Double): Boolean {
        if (!value.isFinite() || value !in 0.25..4.0) return false
        liveSensitivity = value
        return true
    }
    private fun measuredGain(pcm: ShortArray): Double {
        val energy = pcm.fold(0.0) { acc, sample ->
            val normalized = sample.toDouble() / 32768.0
            acc + normalized * normalized
        }
        val rms = kotlin.math.sqrt(energy / pcm.size)
        // A near-silent frame must not amplify the converter's noise floor.
        // Above that floor, quiet *valid* PCM is the very signal that needs gain;
        // the former rms < 0.012 branch forced it back to 1x.
        val target = if (rms < 0.0015) 1.0 else (0.12 / rms).coerceIn(1.0, 8.0)
        val alpha = if (target > adaptiveGain) 0.22 else 0.08
        adaptiveGain = (adaptiveGain + (target - adaptiveGain) * alpha).coerceIn(1.0, 8.0)
        return adaptiveGain
    }
    @Synchronized fun accept(timestampMs: Long, pcm: ShortArray, gain: Double = 1.0): ClassicDjEngine.Output {
        // Compute and validate before dispatch; invalid PCM must never cause lamp output.
        require(gain.isFinite() && gain in 0.1..10.0)
        val corrected = (if (automaticAnalysisGain) measuredGain(pcm) else 1.0) *
            gain * liveSensitivity
        val bounded = corrected.coerceIn(0.1, 10.0)
        return engine.audio(timestampMs, spectral.bands(pcm), bounded)
    }
}
