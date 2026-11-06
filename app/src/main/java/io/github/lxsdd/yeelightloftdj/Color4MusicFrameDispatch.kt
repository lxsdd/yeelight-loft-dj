package io.github.lxsdd.yeelightloftdj

/**
 * RC3 sent no RGB frames at all unless the bass-transient detector returned
 * beat=true. A stable real music signal can have no qualifying transient.
 *
 * This gated fallback is ONLY for the explicitly initiated, single-Color4
 * eight-second reverse-channel acceptance test. It is NEVER a substitute
 * for classic LAN TCP writes, nor does it fabricate detected beats or BPM.
 */
internal enum class MusicFrameDispatch { NONE, BEAT, ACCENT, AUDIO_ACTIVITY }

internal object Color4MusicFrameDispatch {
    private const val MIN_ACTIVITY_INTERVAL_MS = 1_000L
    // At most 2 measured non-bass accent writes per second and ONLY through
    // the explicitly approved, already connected reverse-music channel.
    private const val MIN_ACCENT_INTERVAL_MS = 500L

    fun isAudible(frame: MusicFrame): Boolean =
        frame.rms.isFinite() && frame.peak.isFinite() &&
            frame.rms >= 0.025f && frame.peak >= 0.08f &&
            frame.bands.any { it.isFinite() && it >= 0.04f }

    fun decide(
        frame: MusicFrame,
        previousSentAt: Long,
        nowMs: Long,
        acceptanceTest: Boolean,
        allStreaming: Boolean,
    ): MusicFrameDispatch {
        val beatInterval = if (allStreaming) 350L else MusicLightPolicy.minCommandIntervalMs
        if (MusicLightPolicy.maySend(frame, previousSentAt, nowMs, beatInterval)) {
            return MusicFrameDispatch.BEAT
        }
        // A real onset from the 6-band FFT can also trigger a Color4
        // acceptance frame. Absolutely no accent bypass of standard LAN
        // command pacing, 8-second deadline, or model/SUPPORT gates.
        if (acceptanceTest && allStreaming && isAudible(frame) &&
            frame.accent != null && nowMs >= previousSentAt &&
            nowMs - previousSentAt >= MIN_ACCENT_INTERVAL_MS
        ) return MusicFrameDispatch.ACCENT

        // Never weaken normal user shows or send continuous Color4 updates
        // via the quota-limited classic LAN endpoint. Fallback stays 1/s.
        if (acceptanceTest && allStreaming && isAudible(frame) &&
            nowMs >= previousSentAt &&
            nowMs - previousSentAt >= MIN_ACTIVITY_INTERVAL_MS
        ) return MusicFrameDispatch.AUDIO_ACTIVITY
        return MusicFrameDispatch.NONE
    }
}

internal data class Color4MusicTestMetrics(
    val frames: Long = 0,
    val audible: Long = 0,
    val beats: Long = 0,
    val accents: Long = 0,
    val beatWriteAttempts: Long = 0,
    val accentWriteAttempts: Long = 0,
    val activityWriteAttempts: Long = 0,
    val writeAttempts: Long = 0,
    val writeFailures: Long = 0,
) {
    fun withFrame(frame: MusicFrame): Color4MusicTestMetrics = copy(
        frames = frames + 1,
        audible = audible + if (Color4MusicFrameDispatch.isAudible(frame)) 1 else 0,
        beats = beats + if (frame.beat) 1 else 0,
        accents = accents + if (frame.accent != null) 1 else 0,
    )

    fun withWrites(
        attempts: Int,
        failures: Int,
        dispatch: MusicFrameDispatch = MusicFrameDispatch.AUDIO_ACTIVITY,
    ): Color4MusicTestMetrics {
        require(attempts >= 0 && failures in 0..attempts)
        require(dispatch != MusicFrameDispatch.NONE || attempts == 0)
        return copy(
            beatWriteAttempts = beatWriteAttempts +
                if (dispatch == MusicFrameDispatch.BEAT) attempts else 0,
            accentWriteAttempts = accentWriteAttempts +
                if (dispatch == MusicFrameDispatch.ACCENT) attempts else 0,
            activityWriteAttempts = activityWriteAttempts +
                if (dispatch == MusicFrameDispatch.AUDIO_ACTIVITY) attempts else 0,
            writeAttempts = writeAttempts + attempts,
            writeFailures = writeFailures + failures
        )
    }

    fun runningReport(): String =
        "RC-Musiktest · Audio: $audible/$frames Frames · Beats: $beats · " +
            "Akzente: $accents · RGB-Sendeversuche: $writeAttempts " +
            "(Beat $beatWriteAttempts / Akzent $accentWriteAttempts / " +
            "Aktivität $activityWriteAttempts) · Übertragungsfehler: $writeFailures"

    fun finishedReport(): String {
        val sent = writeAttempts - writeFailures
        val outcome = when {
            frames == 0L -> "Keine Audioframes während aktivem Musikkanal"
            audible == 0L -> "Audioframes empfangen, aber kein ausreichend lautes Musiksignal"
            writeAttempts == 0L -> "Messbares Audiosignal, aber kein RGB-Sendeversuch"
            sent == 0L -> "Alle RGB-Sendeversuche sind fehlgeschlagen"
            else -> "$sent RGB-Telegramm(e) an den Rückkanal geschrieben"
        }
        return "Color4-Test beendet · $outcome · " +
            "Audio $audible/$frames · Beats $beats · Akzente $accents · " +
            "RGB-Versuche: Beat $beatWriteAttempts / Akzent $accentWriteAttempts / " +
            "Aktivität $activityWriteAttempts · Fehler $writeFailures. " +
            "Senden ist keine optische Bestätigung."
    }
}
