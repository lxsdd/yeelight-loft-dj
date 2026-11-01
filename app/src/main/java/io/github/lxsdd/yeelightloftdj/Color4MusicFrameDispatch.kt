package io.github.lxsdd.yeelightloftdj

/**
 * RC3 sent no RGB frames at all unless the bass-transient detector returned
 * beat=true. A stable real music signal can have no qualifying transient.
 *
 * This gated fallback is ONLY for the explicitly initiated, single-Color4
 * eight-second reverse-channel acceptance test. It is NEVER a substitute
 * for classic LAN TCP writes, nor does it fabricate detected beats or BPM.
 */
internal enum class MusicFrameDispatch { NONE, BEAT, AUDIO_ACTIVITY }

internal object Color4MusicFrameDispatch {
    private const val MIN_ACTIVITY_INTERVAL_MS = 1_000L

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
        // Never weaken normal user shows or send continuous Color4 updates
        // via the quota-limited classic LAN endpoint.
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
    val writeAttempts: Long = 0,
    val writeFailures: Long = 0,
) {
    fun withFrame(frame: MusicFrame): Color4MusicTestMetrics = copy(
        frames = frames + 1,
        audible = audible + if (Color4MusicFrameDispatch.isAudible(frame)) 1 else 0,
        beats = beats + if (frame.beat) 1 else 0,
    )

    fun withWrites(attempts: Int, failures: Int): Color4MusicTestMetrics {
        require(attempts >= 0 && failures in 0..attempts)
        return copy(writeAttempts = writeAttempts + attempts,
            writeFailures = writeFailures + failures)
    }

    fun runningReport(): String =
        "RC-Musiktest · Audio: $audible/$frames Frames · Beats: $beats · " +
            "RGB-Sendeversuche: $writeAttempts · Übertragungsfehler: $writeFailures"

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
            "Audio $audible/$frames · Beats $beats · " +
            "Fehler $writeFailures. Senden ist keine optische Bestätigung."
    }
}
