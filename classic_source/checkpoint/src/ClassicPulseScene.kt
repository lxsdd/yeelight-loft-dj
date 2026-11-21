package loftdj.classic

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Side-effect-free, stateful pulse envelope for already-owned Music frames.
 * A brightness frame at 1% on Color4/1S is visibly ON, NEVER power=OFF.
 * Gamma shapes perception; no hardware luminance calibration is assumed.
 */
internal class ClassicPulseScene {
    data class Options(
        val rgb: Int = 0x25CBE8,
        val beatSync: Boolean = true,
        val bpm: Int = 120,
        val minBrightness: Int = 12,
        val maxBrightness: Int = 90,
        val attackMs: Int = 90,
        val decayMs: Int = 600,
        val gamma: Double = 2.0
    ) {
        init {
            require(rgb in 0..0xFFFFFF)
            require(bpm in 40..240)
            require(minBrightness in 1..99)
            require(maxBrightness in (minBrightness + 1)..100)
            require(attackMs in 50..350)
            require(decayMs in 200..1800)
            require(gamma.isFinite() && gamma in 0.75..4.0)
        }
    }
    private var options = Options()
    private var anchorMs = -1L
    private var lastBeatMs = -1L
    fun configure(value: Options) { options = value; reset() }
    fun reset() { anchorMs = -1L; lastBeatMs = -1L }
    fun rgb(): Int = options.rgb

    /** Sample every audio callback, before bounded output pacing. */
    fun brightness(timestampMs: Long, beat: Boolean): Int {
        require(timestampMs >= 0L)
        if (anchorMs < 0L) anchorMs = timestampMs
        if (options.beatSync && beat &&
            (lastBeatMs < 0L || timestampMs - lastBeatMs >= 250L))
            lastBeatMs = timestampMs
        val age = if (options.beatSync) {
            if (lastBeatMs < 0L) Long.MAX_VALUE else (timestampMs - lastBeatMs).coerceAtLeast(0L)
        } else {
            val period = 60_000L / options.bpm
            (timestampMs - anchorMs) % period
        }
        val envelope = when {
            age == Long.MAX_VALUE -> 0.0
            age < options.attackMs -> age.toDouble() / options.attackMs.toDouble()
            else -> exp(-(age - options.attackMs).toDouble() / options.decayMs.toDouble())
        }.coerceIn(0.0, 1.0)
        return (options.minBrightness +
            (options.maxBrightness - options.minBrightness) * envelope.pow(options.gamma))
            .roundToInt().coerceIn(options.minBrightness, options.maxBrightness)
    }
}
