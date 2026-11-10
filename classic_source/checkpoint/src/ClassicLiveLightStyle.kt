package loftdj.classic

import kotlin.math.abs

/**
 * Deterministic, side-effect-free renderer for the already-confirmed Music reverse channel.
 * It does not discover devices, open sockets, trigger set_music, or make LAN RPCs.
 * Only ClassicDjEngine forwards a frame, and only for its currently owned participants.
 *
 * Quiet sections evolve through small HSV hue steps; beats rotate to contrasting hues.
 * Brightness follows a smoothed weighted band level. This is output-rate bounded,
 * not a claim about any Color4 hardware's supported sustained command rate.
 */
internal class ClassicLiveLightStyle(
    private val minFrameIntervalMs: Long = 300L,
    private val quietThreshold: Double = 0.26,
) {
    data class Frame(val rgb: Int, val brightness: Int)

    private var lastTimestampMs = -1L
    private var lastFrameMs = -1L
    private var hueDegrees = 24.0
    private var smoothLevel = 0.0
    private var nextBeatHue = 0

    init {
        require(minFrameIntervalMs in 200L..2_000L)
        require(quietThreshold.isFinite() && quietThreshold in 0.05..0.5)
    }

    fun reset() {
        lastTimestampMs = -1L
        lastFrameMs = -1L
        hueDegrees = 24.0
        smoothLevel = 0.0
        nextBeatHue = 0
    }

    /** Returns at most one frame per interval, even under arbitrarily fast beat callbacks. */
    fun accept(timestampMs: Long, beat: Boolean, bass: Double, mid: Double,
               presence: Double, treble: Double): Frame? {
        require(timestampMs >= 0)
        val bands = doubleArrayOf(bass, mid, presence, treble)
        require(bands.all { it.isFinite() && it in 0.0..1.0 })
        if (lastTimestampMs >= 0 && timestampMs <= lastTimestampMs) {
            reset() // replayed / backwards audio must not send another frame
            lastTimestampMs = timestampMs
            return null
        }
        val delta = if (lastTimestampMs < 0) 0L else timestampMs - lastTimestampMs
        lastTimestampMs = timestampMs
        val level = (bass * 0.50 + mid * 0.25 + presence * 0.15 + treble * 0.10).coerceIn(0.0, 1.0)
        // Repeated short PCM updates and sparse updates both behave predictably.
        val alpha = if (delta <= 0) 1.0 else (delta.toDouble() / (delta + 170.0)).coerceIn(0.02, 0.7)
        smoothLevel += (level - smoothLevel) * alpha

        // A confirmed beat supplies a distinct hue, never an unbounded output burst.
        // In quiet sections a gradual, less intrusive sweep remains active.
        if (beat) {
            nextBeatHue = (nextBeatHue + 137) % 360
            hueDegrees = nextBeatHue.toDouble()
        } else if (smoothLevel <= quietThreshold) {
            hueDegrees = (hueDegrees + delta.coerceAtMost(500L) * 0.006) % 360.0
        }
        if (lastFrameMs >= 0 && timestampMs - lastFrameMs < minFrameIntervalMs) return null
        lastFrameMs = timestampMs
        val brightness = (12 + smoothLevel * 88).toInt().coerceIn(12, 100)
        return Frame(hsvRgb(hueDegrees), brightness)
    }

    private fun hsvRgb(hue: Double): Int {
        val h = ((hue % 360.0) + 360.0) % 360.0 / 60.0
        val x = (255.0 * (1.0 - abs(h % 2.0 - 1.0))).toInt().coerceIn(0, 255)
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(255, x, 0)
            1 -> Triple(x, 255, 0)
            2 -> Triple(0, 255, x)
            3 -> Triple(0, x, 255)
            4 -> Triple(x, 0, 255)
            else -> Triple(255, 0, x)
        }
        return (r shl 16) or (g shl 8) or b
    }
}
