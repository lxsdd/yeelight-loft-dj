package io.github.lxsdd.yeelightloftdj

/**
 * Only for an explicitly user-initiated, eight-second SINGLE Color4 on a
 * verified set_music reverse channel. It never writes normal-LAN RGB.
 *
 * A 130-BPM beat is ~462 ms apart; the proven baseline trial is one frame/s.
 * A separate opt-in tempo trial can send at most 16 reverse-socket RGB frames
 * with >=450 ms between them. No inferred BPM or synthetic beat events.
 */
internal object Color4TempoProbePolicy {
    const val MIN_BEAT_SPACING_MS = 450L
    const val FALLBACK_ACTIVITY_SPACING_MS = 1_000L
    const val MAX_RGB_FRAMES = 16

    fun decide(
        frame: MusicFrame?,
        beatSinceLastSend: Boolean,
        elapsedMs: Long,
        lastSentMs: Long,
        attempts: Int,
    ): MusicFrameDispatch {
        if (frame == null || attempts !in 0 until MAX_RGB_FRAMES ||
            elapsedMs !in 0 until MinimalColor4ProbePolicy.DURATION_MS ||
            elapsedMs < lastSentMs || !Color4MusicFrameDispatch.isAudible(frame)
        ) return MusicFrameDispatch.NONE
        val elapsedSinceSend = elapsedMs - lastSentMs
        // Fresh, measured beat events are latched by the controller. The
        // audio UI may have already rendered another 64-ms frame by now.
        if (beatSinceLastSend && elapsedSinceSend >= MIN_BEAT_SPACING_MS)
            return MusicFrameDispatch.BEAT
        // A visible fallback must be labelled "audio activity", NOT "beat".
        if (!beatSinceLastSend && elapsedSinceSend >= FALLBACK_ACTIVITY_SPACING_MS)
            return MusicFrameDispatch.AUDIO_ACTIVITY
        return MusicFrameDispatch.NONE
    }
}
