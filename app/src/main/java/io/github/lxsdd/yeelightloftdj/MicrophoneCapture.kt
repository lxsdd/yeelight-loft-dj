package io.github.lxsdd.yeelightloftdj

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-initiated, foreground-only microphone input. The calling Activity checks
 * RECORD_AUDIO first. No recording to disk, cloud, analytics or background service.
 */
class MicrophoneCapture {
    private val enabled = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    val sampleRate = 16000
    val frameSize = 2048

    @SuppressLint("MissingPermission")
    suspend fun run(onFrame: suspend (MusicFrame) -> Unit) = withContext(Dispatchers.IO) {
        check(enabled.compareAndSet(false, true)) { "Mikrofon läuft bereits" }
        val analyzer = MusicAnalyzer(sampleRate, frameSize)
        try {
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Das Mikrofon unterstützt 16-kHz-PCM nicht" }
            val audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minimum * 2, frameSize * 2 * 4))
                .build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon konnte nicht initialisiert werden" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Mikrofonaufnahme wurde vom Gerät verweigert"
            }
            val samples = ShortArray(frameSize)
            while (enabled.get()) {
                var count = 0
                while (enabled.get() && count < samples.size) {
                    val n = audio.read(samples, count, samples.size - count, AudioRecord.READ_BLOCKING)
                    if (n < 0) error("Mikrofon-Lesefehler ($n)")
                    if (n == 0) continue
                    count += n
                }
                if (enabled.get() && count == samples.size) {
                    onFrame(analyzer.analyze(samples, android.os.SystemClock.elapsedRealtime()))
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
        enabled.set(false)
        // Stop unblocks READ_BLOCKING; release remains owned by the IO worker.
        try { recorder?.stop() } catch (_: Exception) { }
    }
}
