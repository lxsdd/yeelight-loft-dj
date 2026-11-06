package io.github.lxsdd.yeelightloftdj

import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/** Android capture presets. A preset does NOT select an analog microphone gain. */
enum class MicrophoneInputProfile(
    val title: String,
    val description: String,
    val source: Int
) {
    MIC("Normal (MIC)", "Androids normaler Mikrofoneingang", MediaRecorder.AudioSource.MIC),
    VOICE_RECOGNITION("Spracherkennung", "Weniger sprachverarbeitende Effekte auf kompatiblen Geräten",
        MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION("Freisprechen (Vergleich)", "Sprachverarbeitung kann Musik/Bass unterdrücken",
        MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    UNPROCESSED("Unverarbeitet (sofern unterstützt)", "Rohsignalprofil ohne garantierte analoge Verstärkung",
        MediaRecorder.AudioSource.UNPROCESSED);

    companion object {
        fun restore(raw: String?): MicrophoneInputProfile =
            entries.firstOrNull { it.name == raw } ?: MIC
        fun available(rawSupported: Boolean): List<MicrophoneInputProfile> =
            entries.filter { it != UNPROCESSED || rawSupported }
    }
}

/** Measured from Android AudioRecord samples BEFORE any app-side multiplication. */
internal data class RawMicLevel(val rms: Float, val peak: Float, val clipped: Int) {
    companion object {
        fun from(pcm: ShortArray): RawMicLevel {
            require(pcm.isNotEmpty())
            var energy = 0.0
            var maxSample = 0
            var clipped = 0
            for (sample in pcm) {
                val sampleInt = sample.toInt()
                energy += sampleInt.toDouble() * sampleInt
                val magnitude = abs(sampleInt)
                if (magnitude > maxSample) maxSample = magnitude
                if (magnitude >= 32767) clipped++
            }
            return RawMicLevel(
                (sqrt(energy / pcm.size) / 32768.0).toFloat(),
                maxSample.toFloat() / 32768f,
                clipped
            )
        }
    }

    fun rmsDbfs(): Float = dbfs(rms)
    fun peakDbfs(): Float = dbfs(peak)

    private fun dbfs(value: Float): Float =
        if (value <= 0f) -120f else (20.0 * log10(value.toDouble())).toFloat()
}

/** Audio-only, no recording storage and no access to Yeelight transport. */
data class MicDiagnostics(
    val rawRmsDbfs: Float,
    val rawPeakDbfs: Float,
    val rawClipping: Int,
    val outputClipping: Int,
    val gain: Float,
    val profile: String,
    val routedInput: String,
)

/**
 * Digital analysis AGC: no microphone hardware gain changes.
 * Only raises gain on genuine raw sound, with a raw-peak ceiling.
 * Does not boost silence. One reevaluation per 1.5 seconds except clipping.
 */
internal class AdaptiveMicrophoneGain {
    var gain: Float = 1f
        private set
    private var lastUpdate: Long? = null

    fun observe(raw: RawMicLevel, nowMs: Long): Float {
        if (!raw.rms.isFinite() || !raw.peak.isFinite()) return gain
        if (raw.clipped > 0) {
            // Once Android's PCM is clipped the lost peaks cannot be recovered;
            // never amplify the already damaged samples further.
            gain = .5f
            lastUpdate = nowMs
            return gain
        }
        if (raw.rms < .003f || raw.peak < .008f) return gain
        val urgent = raw.peak * gain > .92f
        val prev = lastUpdate
        if (!urgent && prev != null && nowMs >= prev && nowMs - prev < 1_500L) return gain
        val targetByRms = .09f / raw.rms
        val safeByPeak = .78f / raw.peak
        val desired = MicrophoneAnalysisGain.normalize(
            min(targetByRms, safeByPeak).coerceIn(.5f, 4f)
        )
        // Hysteresis: no repeated transient resets for tiny gain differences.
        if (abs(desired - gain) >= .5f || urgent) {
            gain = desired
            lastUpdate = nowMs
        }
        return gain
    }
}
