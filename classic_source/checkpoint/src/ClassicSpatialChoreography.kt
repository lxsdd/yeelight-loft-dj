package loftdj.classic

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pure ordered spatial choreography. Uses explicitly selected AND already
 * reverse-channel-OWNED participants only; it cannot discover, select,
 * power on/off or command a lamp. Unlit tail uses 1% (visible), not OFF.
 *
 * The existing ClassicLiveLightStyle rate limiter still gates delivery.
 * Simulated layouts can contain up to 14 lamps, but this is NOT an assertion
 * that OEM Classic has qualified a 14-device Music socket budget.
 */
internal class ClassicSpatialChoreography {
    enum class Mode { SCANNER, METEOR, WAVE }

    fun frame(mode: Mode, index: Int, count: Int, timestampMs: Long,
              loudness: Double, beat: Boolean): ClassicLiveLightStyle.Frame {
        require(count in 1..14 && index in 0 until count)
        require(timestampMs >= 0)
        require(loudness.isFinite() && loudness in 0.0..1.0)
        val intensity = when (mode) {
            Mode.SCANNER -> {
                val span = (count - 1).coerceAtLeast(1)
                val cycle = span * 2
                val phase = ((timestampMs / 210L) % cycle).toInt()
                val head = if (phase <= span) phase else cycle - phase
                val distance = abs(index - head)
                when (distance) {
                    0 -> 1.0
                    1 -> 0.22
                    else -> 0.0
                }
            }
            Mode.METEOR -> {
                val cycle = count + 4
                val head = ((timestampMs / 165L) % cycle).toInt()
                val age = head - index
                if (age in 0..4) exp(-age.toDouble() * 0.75) else 0.0
            }
            Mode.WAVE -> {
                val phase = timestampMs.toDouble() / 500.0 - index * 0.7
                (0.5 + 0.5 * sin(phase)).coerceIn(0.0, 1.0)
            }
        }
        // Beat accent and measured source loudness influence the same pure
        // pattern without overriding the participant ownership or ordering.
        val energy = (0.32 + loudness * 0.5 + if (beat) 0.18 else 0.0)
            .coerceIn(0.0, 1.0)
        val brightness = (1.0 + intensity * energy * 99.0)
            .toInt().coerceIn(1, 100)
        val color = when (mode) {
            Mode.SCANNER -> 0xFF2456
            Mode.METEOR -> 0xFF7C32
            Mode.WAVE -> 0x26BCD8
        }
        return ClassicLiveLightStyle.Frame(color, brightness)
    }
}
