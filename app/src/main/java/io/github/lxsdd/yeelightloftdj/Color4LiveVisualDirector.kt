package io.github.lxsdd.yeelightloftdj

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Beat mode preserves familiar timing. Adaptive is explicitly opted into before starting. */
enum class Color4LiveStyle(val label: String, val description: String) {
    BEAT("Beat", "Beat-gesteuerte Impulse mit dem vollen Farbspektrum; keine Helligkeitsbefehle."),
    ADAPTIVE("Dynamisch", "Sanfte Farbverläufe und zur gemessenen Musik passende Helligkeit; neue Music-Funktion.")
}

data class Color4MusicVisual(
    val rgb: Int,
    val transitionMs: Int,
    val brightness: Int?,
) {
    init {
        require(rgb in 0..0xFFFFFF)
        require(transitionMs == 0 || transitionMs in 100..5_000)
        require(brightness == null || brightness in 1..100)
    }
}

/** HSV hue spans the whole circle, spectrally steered by all six measured FFT bands.
 * Spatial offsets give all selected bulbs distinct colors without fixed palettes.
 */
internal object Color4FullSpectrum {
    fun rgb(frame: MusicFrame, lamp: Int, count: Int, step: Long, slow: Boolean): Int {
        require(count > 0 && lamp in 0 until count && step >= 0)
        val bands = frame.bands
        val total = bands.sumOf { it.toDouble().coerceAtLeast(0.0) }
        val centroid = if (total > 0.00001)
            bands.indices.sumOf { it * bands[it].toDouble().coerceAtLeast(0.0) } / total
        else 0.0
        val advance = if (slow) 17.0 else 41.0
        val hue = (centroid * 49.0 + (step % 360) * advance +
            lamp * 360.0 / count) % 360.0
        return hsvToRgb(hue.toFloat(), if (slow) .78f else .89f)
    }

    private fun hsvToRgb(hue: Float, saturation: Float): Int {
        val chroma = saturation
        val section = hue / 60f
        val x = chroma * (1f - abs(section % 2f - 1f))
        val (r, g, b) = when {
            section < 1f -> Triple(chroma, x, 0f)
            section < 2f -> Triple(x, chroma, 0f)
            section < 3f -> Triple(0f, chroma, x)
            section < 4f -> Triple(0f, x, chroma)
            section < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = 1f - chroma
        fun byte(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}

/** Only produces parameter-validated reverse Music frames; never falls back to LAN.
 * Adaptive emits at most one RGB frame per 900 ms/device (1850 ms when quiet),
 * and one brightness update per 1500 ms/device on a meaningful >=5-point change.
 * A valid frame is necessary; no synthetic beats or idle playback.
 */
internal class Color4LiveVisualDirector(
    private val count: Int,
    private val style: Color4LiveStyle,
) {
    init { require(count > 0) }
    private var step = 0L
    private var lastUpdate: Long? = null
    private var lastSend: Long? = null
    private var lastBrightnessAt: Long? = null
    private var lastBrightness: Int? = null
    private var envelope = .12f
    private var peakReference = .08f

    /** RGB modulation also works when the lamp firmware ignores a
     * brightness command inside Music mode. Never dims to invisible black.
     */
    private fun scaleRgb(rgb: Int, level: Float): Int {
        val amount = level.coerceIn(.15f, 1f)
        fun part(shift:Int):Int =
            (((rgb shr shift) and 255) * amount).roundToInt().coerceIn(0,255)
        return (part(16) shl 16) or (part(8) shl 8) or part(0)
    }

    fun next(frame: MusicFrame?, pulse: LiveColorPulse, elapsedMs: Long): List<Color4MusicVisual>? {
        if (frame == null || elapsedMs < 0 || !frame.rms.isFinite() ||
            !frame.peak.isFinite() || frame.rms < 0 || frame.peak < 0 ||
            frame.bands.any { !it.isFinite() || it < 0f }) return null

        if (style == Color4LiveStyle.BEAT) {
            if (pulse == LiveColorPulse.NONE) return null
            val result = (0 until count).map { index ->
                Color4MusicVisual(Color4FullSpectrum.rgb(frame, index, count, step, false), 0, null)
            }
            step++
            return result
        }

        val previous = lastUpdate
        if (previous != null && elapsedMs < previous) return null
        val elapsed = if (previous == null) 0L else
            (elapsedMs - previous).coerceIn(0, 1500L)
        lastUpdate = elapsedMs
        // Pixel 9 and older phones report different absolute microphone RMS.
        // Track a decaying measured peak: this normalizes their real PCM
        // dynamic range without changing gain, fabricating beats, or lifting
        // silence. A loud passage sets the reference; it decays gently.
        val decay = if (previous == null) 1f else
            (1f - elapsed / 30_000f).coerceIn(.90f,1f)
        peakReference = maxOf(.025f, peakReference * decay, frame.rms)
        val target = (frame.rms / peakReference).coerceIn(0f,1f).pow(.80f)
        val tau = if (target > envelope) 420f else 2300f
        val alpha = if (previous == null) 1f else elapsed / (tau + elapsed)
        envelope += (target - envelope) * alpha
        val audible = frame.rms >= .009f && frame.peak >= .03f
        // A continuous energy-dependent middle ground: no binary
        // 'fast flashing' vs 'slow dissolving'. Quiet is slower, medium
        // follows ~0.7-1 s, and strong peaks allow quicker musical accents.
        val interval = if (!audible) 1850L
            else (1150f - 700f * envelope).toLong().coerceIn(450L,1150L)
        if (lastSend != null && elapsedMs - lastSend!! < interval) return null
        lastSend = elapsedMs

        val intensity = (8 + envelope * 86 +
            (if (pulse == LiveColorPulse.DETECTED_BEAT && audible) 6 else 0))
            .roundToInt().coerceIn(8, 96)
        val previousBrightness = lastBrightness
        val brightness = if (
            (previousBrightness == null || abs(intensity - previousBrightness) >= 5) &&
            (lastBrightnessAt == null || elapsedMs - lastBrightnessAt!! >= 1500L)
        ) intensity else null
        if (brightness != null) {
            lastBrightness = brightness
            lastBrightnessAt = elapsedMs
        }
        val smooth = if (!audible) 2200 else
            (1750f - 1380f * envelope).roundToInt().coerceIn(370,1750)
        // RGB itself is scaled as a fallback visible dimming path; optional
        // set_bright is extra only for positively advertised capabilities.
        val rgbLevel = (.17f + .83f * envelope).coerceIn(.17f,1f)
        val results = (0 until count).map { index ->
            val full = Color4FullSpectrum.rgb(frame,index,count,step,true)
            Color4MusicVisual(scaleRgb(full,rgbLevel),smooth,brightness)
        }
        step++
        return results
    }
}
