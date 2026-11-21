package io.github.lxsdd.yeelightclassicdj.runtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import loftdj.classic.ClassicPcmCaptureSession

/**
 * Adapt the previously implemented Loft-DJ AudioPlaybackCapture to the
 * exact same PCM/DSP sink as microphone. No audio/video recording to files.
 * Android must first issue a one-shot MediaProjection consent token.
 */
internal class ClassicPlaybackCaptureInput private constructor(
    private val recorder: AudioRecord,
    private val projection: MediaProjection
) : ClassicPcmCaptureSession.Input {
    private val closed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            revoked.set(true)
            try { recorder.stop() } catch (_: RuntimeException) {}
        }
    }

    init {
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun read(buffer: ShortArray): Int {
        check(!closed.get() && !revoked.get()) { "Android playback capture ended" }
        require(buffer.isNotEmpty())
        return recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { projection.unregisterCallback(callback) } catch (_: RuntimeException) {}
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: RuntimeException) {}
        try { recorder.release() } finally {
            try { projection.stop() } catch (_: RuntimeException) {}
        }
    }

    companion object {
        fun open(projection: MediaProjection, sampleRate: Int = 16000): ClassicPcmCaptureSession.Input {
            require(sampleRate in 8000..48000)
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Playback PCM configuration unsupported" }
            val audio = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 4096))
                .build()
            try {
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "Playback capture unavailable" }
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Playback capture did not start"
                }
                return ClassicPlaybackCaptureInput(audio, projection)
            } catch (failure: Throwable) {
                try { audio.release() } catch (_: RuntimeException) {}
                throw failure
            }
        }
    }
}
