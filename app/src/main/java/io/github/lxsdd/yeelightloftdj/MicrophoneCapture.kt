package io.github.lxsdd.yeelightloftdj

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-initiated, foreground-only microphone input. The calling Activity checks
 * RECORD_AUDIO first. No recording to disk, cloud, analytics or background service.
 */
class MicrophoneCapture {
    private val enabled = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    val sampleRate = 16000
    val frameSize = 1024
    @Volatile private var analysisGain = MicrophoneAnalysisGain.DEFAULT
    @Volatile private var automaticGain = false
    @Volatile private var inputProfile = MicrophoneInputProfile.MIC

    fun setAutomaticGain(enabled: Boolean) { automaticGain = enabled }

    fun setInputProfile(profile: MicrophoneInputProfile) {
        check(!enabled.get()) { "Aufnahmeprofil erst nach Stoppen wechseln." }
        inputProfile = profile
    }

    fun setAnalysisGain(gain: Float) {
        analysisGain = MicrophoneAnalysisGain.normalize(gain)
    }

    @SuppressLint("MissingPermission")
    suspend fun run(
        onFrame: suspend (MusicFrame) -> Unit,
        onDiagnostics: suspend (MicDiagnostics) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        check(!stopped.get()) { "Mikrofon wurde bereits gestoppt" }
        check(enabled.compareAndSet(false, true)) { "Mikrofon läuft bereits" }
        val analyzer = MusicAnalyzer(sampleRate, frameSize)
        var previousGain = analysisGain
        var previousAuto = automaticGain
        val autoGain = AdaptiveMicrophoneGain()
        var reportFrames = 0
        val profile = inputProfile
        try {
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Das Mikrofon unterstützt 16-kHz-PCM nicht" }
            val audio = AudioRecord.Builder()
                .setAudioSource(profile.source)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minimum * 2, frameSize * 2 * 4))
                .build()
            recorder = audio
            check(!stopped.get()) { "Mikrofonaufnahme wurde vor dem Start beendet" }
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon konnte nicht initialisiert werden" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Mikrofonaufnahme wurde vom Gerät verweigert"
            }
            // Preserve the 1024-sample FFT frequency resolution but advance
            // the beat detector every 512 samples (32 ms instead of 64 ms).
            // This reduces onset quantization without raising microphone gain.
            val hop = ShortArray(frameSize / 2)
            val overlap = OverlappingPcmWindows(frameSize)
            val route = audio.routedDevice?.productName?.toString()?.take(80)
                ?: "Android-Standardmikrofon"
            while (enabled.get()) {
                var count = 0
                while (enabled.get() && count < hop.size) {
                    val n = audio.read(hop, count, hop.size - count, AudioRecord.READ_BLOCKING)
                    if (n < 0) error("Mikrofon-Lesefehler ($n)")
                    if (n == 0) continue
                    count += n
                }
                val samples = if (enabled.get() && count == hop.size)
                    overlap.accept(hop) else null
                if (enabled.get() && samples != null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val raw = RawMicLevel.from(samples)
                    val isAuto = automaticGain
                    val gain = if (isAuto) autoGain.observe(raw, now) else analysisGain
                    val processed = MicrophoneAnalysisGain.apply(samples, gain)
                    reportFrames++
                    if (reportFrames % 4 == 0) {
                        val out = RawMicLevel.from(processed)
                        onDiagnostics(MicDiagnostics(
                            rawRmsDbfs = raw.rmsDbfs(),
                            rawPeakDbfs = raw.peakDbfs(),
                            rawClipping = raw.clipped,
                            outputClipping = out.clipped,
                            gain = gain,
                            profile = profile.title,
                            routedInput = route,
                        ))
                    }
                    if (gain != previousGain || isAuto != previousAuto) {
                        previousAuto = isAuto

                        // Preserve measured beat timestamps across digital AGC
                        // adjustments. Only suppress the immediate artificial
                        // spectral step, or BPM would remain null forever.
                        previousGain = gain
                        analyzer.onAnalysisGainChanged()
                        onFrame(analyzer.analyze(processed, now))
                    } else {
                        onFrame(analyzer.analyze(processed, now))
                    }
                }
            }
        } finally {
            enabled.set(false)
            recorder?.let { audio ->
                try { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
                catch (_: Exception) { }
                audio.release()
            }
            recorder = null
        }
    }

    fun stop() {
        stopped.set(true)
        enabled.set(false)
        // Stop unblocks READ_BLOCKING; release remains owned by the IO worker.
        try { recorder?.stop() } catch (_: Exception) { }
    }
}
