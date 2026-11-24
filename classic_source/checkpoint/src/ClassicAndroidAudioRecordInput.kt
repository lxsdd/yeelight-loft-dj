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
            // A device may reject one valid source while allowing the other.
            // Recover only from AudioRecord *state* failure, never from a denied
            // RECORD_AUDIO permission, invalid format or incompatible ABI.
            // This is a bounded one-time startup fallback, not a retry loop.
            val candidates = if (source == MediaRecorder.AudioSource.MIC) {
                intArrayOf(source, MediaRecorder.AudioSource.UNPROCESSED)
            } else intArrayOf(source)
            var primaryFailure: IllegalStateException? = null
            for (candidate in candidates) {
                var allocated: AudioRecord? = null
                try {
                    val record = AudioRecord(candidate, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
                    allocated = record
                    check(record.state == AudioRecord.STATE_INITIALIZED) {
                        "AudioRecord initialization failed"
                    }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "AudioRecord did not start"
                    }
                    return ClassicAndroidAudioRecordInput(record)
                } catch (failure: Throwable) {
                    // Release the failed instance before trying a new source.
                    // Preserve the actual failure if cleanup itself fails.
                    try { allocated?.release() }
                    catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    if (candidate == MediaRecorder.AudioSource.MIC &&
                        source == MediaRecorder.AudioSource.MIC &&
                        failure is IllegalStateException) {
                        primaryFailure = failure
                        continue
                    }
                    primaryFailure?.let { failure.addSuppressed(it) }
                    throw failure
                }
            }
            error("No microphone capture source accepted")
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
