package loftdj.classic

/** Pure, Android-independent bridge. Never creates sessions or network connections implicitly. */
class ClassicDjEngine(
    private val coordinator: ClassicMusicCoordinator,
    private val analyzer: ClassicBeatAnalyzer = ClassicBeatAnalyzer()
) {
    data class Output(val beat: Boolean, val bpm: Double?, val confidence: Double,
                      val sent: Set<String>, val failed: Set<String>, val level: Double = 0.0,
                      // Actual normalized FFT/beat-analyzer bands, not inferred UI bars.
                      val bass: Double = 0.0, val mid: Double = 0.0,
                      val presence: Double = 0.0, val treble: Double = 0.0)
    private val active = linkedMapOf<String, Long>()
    // A failed reverse frame has an uncertain remote outcome. This fact must
    // survive engine.stop(), even after every surviving owned peer was stopped.
    private var outputUncertain = false
    @Synchronized fun uncertainOutput(): Boolean = outputUncertain
    private var lastAudioMs = -1L
    // Both PCM and synthetic clock frames share one monotonic output clock.
    // A stale audio callback must not reset or overtake an already emitted tick.
    private var lastSceneInputMs = -1L
    private var sceneName = "SPECTRUM"
    private var clockPulseEnabled = false
    private val lighting = ClassicLiveLightStyle()
    private val spatial = ClassicSpatialChoreography()
    private var spatialMode: ClassicSpatialChoreography.Mode? = null

    /** Caller must explicitly start and confirm each lamp. Unowned lamps are never output targets. */
    @Synchronized fun select(
        ids: List<String>, onConfirmed: (Int, Int) -> Unit = { _, _ -> },
        allowNextStart: () -> Boolean = { true }
    ): Map<String, ClassicMusicCoordinator.State> {
        require(ids.distinct().size == ids.size)
        val states = coordinator.startGroup(ids,
            onConfirmed = onConfirmed, allowNextStart = allowNextStart)
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
            // Do NOT reset the light renderer or move its clock backwards.
            // A timer frame may already be newer than this stale PCM sample;
            // the next timer tick must continue the same animation and pacing.
            lastAudioMs = timestampMs
            return Output(false, null, 0.0, emptySet(), emptySet())
        }
        val result = analyzer.accept(timestampMs, bands, gain)
        lastAudioMs = timestampMs
        val loudness = (0.5 * result.bass + 0.25 * result.mid +
                        0.15 * result.presence + 0.10 * result.treble).coerceIn(0.0, 1.0)
        // A clock tick can race an old PCM timestamp after a source pause.
        // Preserve telemetry while never replaying or resetting lighting output.
        if (timestampMs <= lastSceneInputMs)
            return Output(result.beat, result.bpm, result.confidence,
                          emptySet(), emptySet(), loudness,
                          result.bass, result.mid, result.presence, result.treble)
        lastSceneInputMs = timestampMs
        val frame = lighting.accept(timestampMs, result.beat, result.bass,
                                    result.mid, result.presence, result.treble)
        val (sent, failed) = dispatchOwned(frame, timestampMs, loudness, result.beat)
        return Output(result.beat, result.bpm, result.confidence, sent, failed,
                      (0.5 * result.bass + 0.25 * result.mid + 0.15 * result.presence + 0.10 * result.treble).coerceIn(0.0, 1.0),
                      result.bass, result.mid, result.presence, result.treble)
    }

    /**
     * The ORIGINAL Classic AudioRecord/Visualizer has already produced a real
     * FFT/beat decision. Send that measured frame through the SAME owned-only
     * spatial/pulse scene transport, without re-analyzing or opening audio.
     * This is called only from the bounded worker of an explicitly enrolled
     * original source session. Never treats an OEM ID as a network address.
     */
    @Synchronized fun originalFrame(timestampMs: Long, bands: DoubleArray,
                                    beat: Boolean, bpm: Double,
                                    confidence: Double): Output {
        require(timestampMs >= 0L && bands.size == 4 &&
                bands.all { it.isFinite() && it in 0.0..1.0 } &&
                bpm.isFinite() && bpm in 0.0..240.0 &&
                confidence.isFinite() && confidence in 0.0..1.0)
        if (timestampMs <= lastSceneInputMs || active.isEmpty())
            return Output(false, null, 0.0, emptySet(), emptySet())
        lastSceneInputMs = timestampMs
        lastAudioMs = timestampMs
        val loudness = (0.5 * bands[0] + 0.25 * bands[1] +
                        0.15 * bands[2] + 0.10 * bands[3]).coerceIn(0.0, 1.0)
        val frame = lighting.accept(timestampMs, beat, bands[0],
                                    bands[1], bands[2], bands[3])
        val (sent, failed) = dispatchOwned(frame, timestampMs, loudness, beat)
        return Output(beat, if (bpm > 0.0) bpm else null, confidence,
                      sent, failed, loudness, bands[0], bands[1],
                      bands[2], bands[3])
    }

    /** Clock-only scene evolution during an already started, explicitly owned
     * music session. The microphone/PCM acquisition and player remain unchanged.
     * This method can NEVER acquire an owner or create a reverse socket.
     */
    @Synchronized fun clockTick(timestampMs: Long): Output {
        require(timestampMs >= 0L)
        val timed = spatialMode != null || (sceneName == "PULSE" && clockPulseEnabled)
        if (!timed || active.isEmpty() || timestampMs <= lastSceneInputMs)
            return Output(false, null, 0.0, emptySet(), emptySet())
        lastSceneInputMs = timestampMs
        val frame = lighting.accept(timestampMs, false, 0.0, 0.0, 0.0, 0.0)
        val (sent, failed) = dispatchOwned(frame, timestampMs, 0.0, false)
        return Output(false, null, 0.0, sent, failed)
    }

    /** The single output route for BOTH audio and timed frames. No normal LAN
     * fallback, extra participant, re-handshake or automatic power command.
     */
    private fun dispatchOwned(frame: ClassicLiveLightStyle.Frame?, timestampMs: Long,
                              loudness: Double, beat: Boolean): Pair<Set<String>, Set<String>> {
        if (frame == null) return emptySet<String>() to emptySet()
        val sent = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        val snapshot = active.toMap()
        val mode = spatialMode
        for ((index, entry) in snapshot.entries.withIndex()) {
            val (id, generation) = entry
            val look = if (mode == null) frame else
                spatial.frame(mode, index, snapshot.size, timestampMs, loudness, beat)
            if (coordinator.frame(id, generation, look.rgb, look.brightness)) sent.add(id)
            else {
                failed.add(id)
                active.remove(id)
                // A reverse write may have failed with an uncertain remote
                // outcome. Never STOP that quarantined peer speculatively.
                // Do not continue a partial light show on the others.
                break
            }
        }
        if (failed.isNotEmpty()) {
            outputUncertain = true
            val survivors = active.keys.toList()
            active.clear() // Fence all later PCM, original and clock frames.
            // Only independently confirmed OWNED peers get their own STOP.
            coordinator.stopGroup(survivors)
        }
        return sent to failed
    }

    /** Spatial settings for the SAME owned scene light writer. Invalid options
     * leave the last known-good geometry untouched and never send a frame.
     */
    @Synchronized fun configureSpatial(rgb: Int, speedPercent: Int,
                                       reverse: Boolean, trailLength: Int,
                                       minBrightness: Int, maxBrightness: Int,
                                       rainbow: Boolean = false): Boolean {
        val proposed = try {
            ClassicSpatialChoreography.Options(rgb, speedPercent, reverse, trailLength,
                                               minBrightness, maxBrightness, rainbow)
        } catch (bad: IllegalArgumentException) { return false }
        spatial.configure(proposed)
        return true
    }

    /** Pure pulse envelope configuration: no socket, power command or new session. */
    @Synchronized internal fun configurePulse(options: ClassicPulseScene.Options): Boolean {
        if (!lighting.configurePulse(options)) return false
        clockPulseEnabled = !options.beatSync
        return true
    }

    /** Switch light scenes on the existing generation-fenced reverse session. */
    @Synchronized fun setScene(scene: String): Boolean {
        val spatial = ClassicSpatialChoreography.Mode.entries.firstOrNull { it.name == scene }
        if (spatial != null) {
            // Retain the already-validated 300ms reverse output budget.
            if (!lighting.setScene("SPECTRUM")) return false
            spatialMode = spatial
            sceneName = scene
            return true
        }
        if (!lighting.setScene(scene)) return false
        spatialMode = null
        sceneName = scene
        return true
    }

    @Synchronized fun stop(): Map<String, ClassicMusicCoordinator.State> {
        val ids = active.keys.toList()
        active.clear()
        analyzer.reset()
        lastAudioMs = -1L
        lastSceneInputMs = -1L
        lighting.reset()
        spatialMode = null
        sceneName = "SPECTRUM"
        clockPulseEnabled = false
        return coordinator.stopGroup(ids)
    }

    @Synchronized fun selected(): Set<String> = active.keys.toSet()

}
