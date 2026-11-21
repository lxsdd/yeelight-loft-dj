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

    /** Scene-gallery settings, not a device or permission contract. -1 selects
     * the original mode colour. All fields are bounded before ANY output.
     */
    data class Options(
        val rgb: Int = -1,
        val speedPercent: Int = 100,
        val reverse: Boolean = false,
        val trailLength: Int = 4,
        val minBrightness: Int = 1,
        val maxBrightness: Int = 100
    ) {
        init {
            require(rgb == -1 || rgb in 0..0xFFFFFF)
            require(speedPercent in 25..400)
            require(trailLength in 1..8)
            require(minBrightness in 1..99)
            require(maxBrightness in (minBrightness + 1)..100)
        }
    }

    private var options = Options()
    /** Only changes pure rendering parameters: never opens or reopens Music. */
    fun configure(value: Options) { options = value }

    fun frame(mode: Mode, index: Int, count: Int, timestampMs: Long,
              loudness: Double, beat: Boolean): ClassicLiveLightStyle.Frame {
        require(count in 1..14 && index in 0 until count)
        require(timestampMs >= 0)
        require(loudness.isFinite() && loudness in 0.0..1.0)
        val position = if (options.reverse) count - 1 - index else index
        val intensity = when (mode) {
            Mode.SCANNER -> {
                val span = (count - 1).coerceAtLeast(1)
                val cycle = span * 2
                val stepMs = 21_000L / options.speedPercent
                val phase = ((timestampMs / stepMs) % cycle).toInt()
                val head = if (phase <= span) phase else cycle - phase
                val distance = abs(position - head)
                when (distance) {
                    0 -> 1.0
                    1 -> 0.22
                    in 2..options.trailLength -> exp(-distance.toDouble() * 1.5) * 0.22
                    else -> 0.0
                }
            }
            Mode.METEOR -> {
                val cycle = count + options.trailLength
                val stepMs = 16_500L / options.speedPercent
                val head = ((timestampMs / stepMs) % cycle).toInt()
                val age = head - position
                if (age in 0..options.trailLength)
                    exp(-age.toDouble() * (3.0 / options.trailLength)) else 0.0
            }
            Mode.WAVE -> {
                val phase = timestampMs.toDouble() * options.speedPercent / 50_000.0 -
                    position * (2.8 / options.trailLength)
                (0.5 + 0.5 * sin(phase)).coerceIn(0.0, 1.0)
            }
        }
        // Beat accent and measured source loudness influence the same pure
        // pattern without overriding the participant ownership or ordering.
        val energy = (0.32 + loudness * 0.5 + if (beat) 0.18 else 0.0)
            .coerceIn(0.0, 1.0)
        val brightness = (options.minBrightness + intensity * energy *
            (options.maxBrightness - options.minBrightness))
            .toInt().coerceIn(options.minBrightness, options.maxBrightness)
        val color = when (mode) {
            Mode.SCANNER -> 0xFF2456
            Mode.METEOR -> 0xFF7C32
            Mode.WAVE -> 0x26BCD8
        }
        return ClassicLiveLightStyle.Frame(if (options.rgb == -1) color else options.rgb,
            brightness)
    }
}
