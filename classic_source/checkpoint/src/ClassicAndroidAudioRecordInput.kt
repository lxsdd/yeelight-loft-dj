package loftdj.classic

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Android-only microphone transport. Permission and foreground-service ownership belong to the caller. */
class ClassicAndroidAudioRecordInput private constructor(
    private val recorder: AudioRecord
) : ClassicPcmCaptureSession.Input {
    private var closed = false

    companion object {
        // Must match ClassicPcmDjBridge default FFT sample rate (16 kHz).
        // A caller using a different rate must construct the bridge with that same rate.
        /** Never starts capture implicitly. Call only after RECORD_AUDIO permission and foreground checks. */
        fun open(sampleRate: Int = 16000, source: Int = MediaRecorder.AudioSource.MIC): ClassicAndroidAudioRecordInput {
            require(sampleRate in 8000..48000)
            require(source == MediaRecorder.AudioSource.MIC || source == MediaRecorder.AudioSource.UNPROCESSED)
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Unsupported microphone configuration" }
            val bytes = maxOf(minimum, 4096)
            val record = AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start" }
                return ClassicAndroidAudioRecordInput(record)
            } catch (failure: Throwable) {
                record.release()
                throw failure
            }
        }
    }

    @Synchronized override fun read(buffer: ShortArray): Int {
        check(!closed) { "Microphone already closed" }
        require(buffer.isNotEmpty())
        // Nonblocking reads avoid holding the capture session lock across an indefinite OS read.
        return recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } finally {
            recorder.release()
        }
    }
}
