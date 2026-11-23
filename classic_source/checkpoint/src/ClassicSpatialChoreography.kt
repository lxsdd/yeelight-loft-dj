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
 * Ordered scenes support the entire dynamically selected participant list.
 * A 14-position test is only a test case, never a product limit or OEM claim.
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
        val maxBrightness: Int = 100,
        val rainbow: Boolean = false
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
        require(count > 0 && index in 0 until count)
        require(timestampMs >= 0)
        require(loudness.isFinite() && loudness in 0.0..1.0)
        val position = if (options.reverse) count - 1 - index else index
        val intensity = when (mode) {
            Mode.SCANNER -> {
                // Long arithmetic keeps the scanner cycle correct as the
                // selected fleet grows beyond earlier fixed test layouts.
                val span = (count.toLong() - 1L).coerceAtLeast(1L)
                val cycle = span * 2L
                val stepMs = 21_000L / options.speedPercent
                // Interpolate between adjacent lamps instead of jumping
                // between integer steps at the bounded Music frame cadence.
                // Modulo BEFORE converting to Double preserves fractional
                // motion even after very long monotonic Android uptimes.
                val phase = (timestampMs % (cycle * stepMs)) / stepMs.toDouble()
                val head = if (phase <= span) phase else cycle - phase
                val distance = abs(position.toDouble() - head)
                when {
                    distance <= 1.0 -> 1.0 - 0.78 * distance
                    distance <= options.trailLength.toDouble() ->
                        0.22 * exp(-(distance - 1.0) * 1.5)
                    else -> 0.0
                }
            }
            Mode.METEOR -> {
                val cycle = count.toLong() + options.trailLength
                val stepMs = 16_500L / options.speedPercent
                val head = (timestampMs % (cycle * stepMs)) / stepMs.toDouble()
                val age = head - position.toDouble()
                when {
                    age < -1.0 || age > options.trailLength -> 0.0
                    // A fractional leading edge fades the next participant in
                    // before arrival, rather than skipping it entirely.
                    age < 0.0 -> 1.0 + age
                    else -> exp(-age * (3.0 / options.trailLength))
                }
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
        val actualRgb = if (options.rainbow) rainbowRgb(position, count, timestampMs)
            else if (options.rgb == -1) color else options.rgb
        return ClassicLiveLightStyle.Frame(actualRgb, brightness)
    }

    /** Pure per-lamp hue; bounded modular arithmetic avoids long-session overflow. */
    private fun rainbowRgb(position: Int, count: Int, timestampMs: Long): Int {
        val phase = ((timestampMs / 45L) % 360L) * options.speedPercent / 100L
        val hue = ((position.toLong() * 360L / count + phase) % 360L) / 60.0
        val x = (255.0 * (1.0 - abs(hue % 2.0 - 1.0))).toInt().coerceIn(0, 255)
        val (r, g, b) = when (hue.toInt()) {
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
