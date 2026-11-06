package loftdj.classic

/** Explicit PCM input bridge; caller owns AudioRecord lifecycle and permission. */
class ClassicPcmDjBridge(
    private val engine: ClassicDjEngine,
    sampleRate: Int = 16000,
    windowSize: Int = 1024
) {
    private val spectral = ClassicPcmBands(sampleRate, windowSize)
    @Synchronized fun accept(timestampMs: Long, pcm: ShortArray, gain: Double = 1.0): ClassicDjEngine.Output {
        // Compute and validate before dispatch; invalid PCM must never cause lamp output.
        require(gain.isFinite() && gain in 0.1..10.0)
        return engine.audio(timestampMs, spectral.bands(pcm), gain)
    }
}
