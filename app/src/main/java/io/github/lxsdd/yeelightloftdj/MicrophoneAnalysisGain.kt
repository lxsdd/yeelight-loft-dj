package io.github.lxsdd.yeelightloftdj

import kotlin.math.roundToInt

/**
 * App-side manual MIC PCM analysis gain only. This does not alter the Android
 * hardware microphone, speaker volume, internal playback capture, or the
 * Color4 transport pacing. Default 1.0 is byte-identical PCM.
 */
internal object MicrophoneAnalysisGain {
    const val DEFAULT = 1f
    const val MIN = .5f
    const val MAX = 4f
    private const val STEP = .25f

    fun normalize(value: Float): Float {
        if (!value.isFinite()) return DEFAULT
        return ((value.coerceIn(MIN, MAX) / STEP).roundToInt() * STEP)
            .coerceIn(MIN, MAX)
    }

    fun apply(pcm: ShortArray, gain: Float): ShortArray {
        val safe = normalize(gain)
        if (safe == DEFAULT) return pcm
        return ShortArray(pcm.size) { i ->
            (pcm[i].toInt() * safe).roundToInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }
}
