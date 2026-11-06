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
    private var beatNumber = 0L

    /** Caller must explicitly start and confirm each lamp. Unowned lamps are never output targets. */
    @Synchronized fun select(ids: List<String>): Map<String, ClassicMusicCoordinator.State> {
        require(ids.distinct().size == ids.size)
        val states = coordinator.startGroup(ids)
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
            lastAudioMs = timestampMs
            return Output(false, null, 0.0, emptySet(), emptySet())
        }
        val result = analyzer.accept(timestampMs, bands, gain)
        lastAudioMs = timestampMs
        val sent = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        if (result.beat) {
            beatNumber++
            // Deterministic full-HSV hue traversal; never emits brightness 0 or treats 1% as off.
            val hue = ((beatNumber * 137L) % 360L).toDouble()
            val rgb = hsvRgb(hue)
            val brightness = (20 + (result.bass * 80).toInt()).coerceIn(20, 100)
            for ((id, generation) in active.toMap()) {
                if (coordinator.frame(id, generation, rgb, brightness)) sent.add(id)
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
        beatNumber = 0L
        return coordinator.stopGroup(ids)
    }

    @Synchronized fun selected(): Set<String> = active.keys.toSet()

    private fun hsvRgb(hue: Double): Int {
        val h = ((hue % 360.0) + 360.0) % 360.0 / 60.0
        val x = (255.0 * (1.0 - kotlin.math.abs(h % 2.0 - 1.0))).toInt().coerceIn(0,255)
        val (r,g,b) = when (h.toInt()) {
            0 -> Triple(255,x,0); 1 -> Triple(x,255,0); 2 -> Triple(0,255,x)
            3 -> Triple(0,x,255); 4 -> Triple(x,0,255); else -> Triple(255,0,x)
        }
        return (r shl 16) or (g shl 8) or b
    }
}
