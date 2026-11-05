package io.github.lxsdd.yeelightloftdj

import kotlin.math.roundToLong

/** A sent color can be sourced from a detected event, a measured BPM grid,
 * a spectral attack, or audible fallback. Only DETECTED means counted beat. */
internal enum class LiveColorPulse { NONE, DETECTED_BEAT, BPM_GRID, SPECTRAL_ACCENT, AUDIBLE_FALLBACK }

/**
 * User-armed, one device, existing quota-free Music reverse channel only.
 * Never predicts a BPM until enough observed audio beats established a
 * non-null measured tempo; never extrapolates indefinitely into missing audio.
 */
internal class Color4LivePulseClock(startBeatCounter: Long) {
    private var seenBeats = startBeatCounter
    private var lastDetectedAt: Long? = null
    private var nextGridAt: Long? = null
    private var lastPulseAt = Long.MIN_VALUE / 4

    companion object {
        const val MIN_SEND_SPACING_MS = 280L
        const val FALLBACK_SPACING_MS = 1_200L
        const val MAX_EXTRAPOLATED_BEATS = 3L
    }

    fun decide(frame: MusicFrame?, totalDetectedBeats: Long, nowMs: Long): LiveColorPulse {
        if (frame == null || nowMs < 0L ||
            !frame.rms.isFinite() || !frame.peak.isFinite() ||
            frame.rms < .012f || frame.peak < .045f ||
            frame.bands.none { it.isFinite() && it >= .02f }) return LiveColorPulse.NONE

        if (totalDetectedBeats < seenBeats) {
            seenBeats = totalDetectedBeats
            nextGridAt = null
            lastDetectedAt = null
        }
        val measuredPeriod = frame.estimatedBpm?.takeIf { it in 50..188 }
            ?.let { (60_000.0 / it).roundToLong() }
        if (totalDetectedBeats > seenBeats) {
            seenBeats = totalDetectedBeats
            lastDetectedAt = nowMs
            nextGridAt = measuredPeriod?.let { nowMs + it }
            if (nowMs - lastPulseAt >= MIN_SEND_SPACING_MS) {
                lastPulseAt = nowMs
                return LiveColorPulse.DETECTED_BEAT
            }
            return LiveColorPulse.NONE
        }

        val anchor = lastDetectedAt
        val grid = nextGridAt
        if (measuredPeriod != null && anchor != null && grid != null &&
            nowMs >= grid && nowMs - lastPulseAt >= MIN_SEND_SPACING_MS &&
            // The callback/poll loop runs asynchronously: allow modest
            // scheduling jitter at the THIRD and final measured-grid pulse.
            // This never extends into a fourth unmeasured beat.
            nowMs - anchor <= measuredPeriod * MAX_EXTRAPOLATED_BEATS +
                maxOf(50L, measuredPeriod / 8L)
        ) {
            // Resynchronize on the next detected beat; no endless metronome.
            nextGridAt = grid + measuredPeriod
            lastPulseAt = nowMs
            return LiveColorPulse.BPM_GRID
        }
        if (frame.accent != null && nowMs - lastPulseAt >= 400L) {
            lastPulseAt = nowMs
            return LiveColorPulse.SPECTRAL_ACCENT
        }
        if (lastPulseAt < 0L || nowMs - lastPulseAt >= FALLBACK_SPACING_MS) {
            lastPulseAt = nowMs
            return LiveColorPulse.AUDIBLE_FALLBACK
        }
        return LiveColorPulse.NONE
    }
}
