package loftdj.classic

/** Pure, Android-independent bridge. Never creates sessions or network connections implicitly. */
class ClassicDjEngine(
    private val coordinator: ClassicMusicCoordinator,
    private val analyzer: ClassicBeatAnalyzer = ClassicBeatAnalyzer()
) {
    data class Output(val beat: Boolean, val bpm: Double?, val confidence: Double,
                      val sent: Set<String>, val failed: Set<String>, val level: Double = 0.0)
    private val active = linkedMapOf<String, Long>()
    private var lastAudioMs = -1L
    private val lighting = ClassicLiveLightStyle()

    /** Caller must explicitly start and confirm each lamp. Unowned lamps are never output targets. */
    @Synchronized fun select(ids: List<String>, allowNextStart: () -> Boolean = { true }): Map<String, ClassicMusicCoordinator.State> {
        require(ids.distinct().size == ids.size)
        val states = coordinator.startGroup(ids, allowNextStart)
        val snapshot = coordinator.snapshot().associateBy { it.id }
        for ((id, state) in states) {
            if (state == ClassicMusicCoordinator.State.OWNED) active[id] = snapshot.getValue(id).generation
            else active.remove(id)
        }
        return states
    }

    /** Frame input must be four validated spectral bands; timestamp is monotonic. */
    @Synchronized fun audio(timestampMs: Long, bands: DoubleArray, gain: Double = 1.0): Output {
        require(timestampMs >= 0)
        if (lastAudioMs >= 0 && timestampMs <= lastAudioMs) {
            // A discontinuity invalidates tempo and pending output, not remote ownership.
            analyzer.reset()
            lighting.reset()
            lastAudioMs = timestampMs
            return Output(false, null, 0.0, emptySet(), emptySet())
        }
        val result = analyzer.accept(timestampMs, bands, gain)
        lastAudioMs = timestampMs
        val sent = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        // The renderer is pure; actual frames remain confined to per-device OWNED
        // reverse channels, with generations checked by ClassicMusicCoordinator.
        val frame = lighting.accept(timestampMs, result.beat, result.bass,
                                    result.mid, result.presence, result.treble)
        if (frame != null) {
            for ((id, generation) in active.toMap()) {
                if (coordinator.frame(id, generation, frame.rgb, frame.brightness)) sent.add(id)
                else { failed.add(id); active.remove(id) }
            }
        }
        return Output(result.beat, result.bpm, result.confidence, sent, failed,
                      (0.5 * result.bass + 0.25 * result.mid + 0.15 * result.presence + 0.10 * result.treble).coerceIn(0.0, 1.0))
    }

    @Synchronized fun stop(): Map<String, ClassicMusicCoordinator.State> {
        val ids = active.keys.toList()
        active.clear()
        analyzer.reset()
        lastAudioMs = -1L
        lighting.reset()
        return coordinator.stopGroup(ids)
    }

    @Synchronized fun selected(): Set<String> = active.keys.toSet()

}
