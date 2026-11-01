package io.github.lxsdd.yeelightloftdj

/**
 * Classifies whether authorized playback capture actually contains a signal.
 * Silence alone CANNOT distinguish an app's capture policy, Cast playback,
 * a paused player, or a genuinely quiet track.
 *
 * Uses elapsed time, not a fixed frame count: supports both 128-ms and
 * reduced 64-ms analysis windows without changing the timeout semantics.
 */
internal enum class PlaybackSignalState { AUDIBLE, QUIET, NO_SIGNAL }

internal class PlaybackSignalWatch {
    private var quietSinceMs: Long? = null
    private var lastAtMs: Long? = null

    fun reset() {
        quietSinceMs = null
        lastAtMs = null
    }

    fun observe(rms: Float, timeMs: Long): PlaybackSignalState {
        val priorTime = lastAtMs
        if (priorTime != null && timeMs < priorTime) reset()
        lastAtMs = timeMs

        if (rms.isFinite() && rms >= 0.004f) {
            quietSinceMs = null
            return PlaybackSignalState.AUDIBLE
        }

        val start = quietSinceMs ?: timeMs.also { quietSinceMs = it }
        return if (timeMs >= start && timeMs - start >= 2_500L) {
            PlaybackSignalState.NO_SIGNAL
        } else PlaybackSignalState.QUIET
    }
}
