package loftdj.classic

import android.media.audiofx.Visualizer

/**
 * Deliberately explicit alternative to MediaProjection for a VERIFIED audio-session ID.
 * The OEM Music/Disco callback supplying that ID is not qualified yet: no UI may
 * assume that session 0 is an unrestricted player capture.
 *
 * Visualizer yields 8-bit unsigned waveform windows, NOT full-resolution PCM.
 * Bounded nearest-neighbour conversion to 16-kHz 16-bit PCM lets the existing
 * FFT/beat/session pipeline consume these limited observations without a second
 * DSP or any recording to files, network or permanent storage.
 *
 * RECORD_AUDIO is required. Session 0 (output mix) also requires the normal
 * MODIFY_AUDIO_SETTINGS permission and may be restricted by Android/player.
 */
class ClassicAndroidVisualizerInput private constructor(
    private val visualizer: Visualizer,
    private val outputRate: Int
) : ClassicPcmCaptureSession.Input, Visualizer.OnDataCaptureListener {
    private val lock = Any()
    private val ring = ShortArray(MAX_BUFFERED_SAMPLES)
    private var first = 0
    private var count = 0
    private var sourceRate = 0
    private var nextPosition = 0.0
    private var closed = false

    companion object {
        private const val CAPTURE_SIZE = 1024
        // A bounded 512-ms window at 16 kHz; stale unconsumed callbacks drop
        // their oldest data instead of growing unbounded or delaying beat timing.
        private const val MAX_BUFFERED_SAMPLES = 8192

        /** No implicit start, no AudioPlaybackCapture token, no permission bypass. */
        fun open(audioSessionId: Int, sampleRate: Int = 16_000): ClassicAndroidVisualizerInput {
            require(audioSessionId >= 0) { "Audio session identity is missing" }
            require(sampleRate == 16_000) { "Existing FFT pipeline requires 16 kHz" }
            val effect = Visualizer(audioSessionId)
            try {
                val sizes = Visualizer.getCaptureSizeRange()
                check(sizes.size == 2 && CAPTURE_SIZE in sizes[0]..sizes[1]) {
                    "Visualizer waveform capture size unsupported"
                }
                check(effect.setCaptureSize(CAPTURE_SIZE) == Visualizer.SUCCESS) {
                    "Visualizer capture configuration rejected"
                }
                val input = ClassicAndroidVisualizerInput(effect, sampleRate)
                val maxRate = Visualizer.getMaxCaptureRate()
                check(maxRate > 0) { "Visualizer callback rate unavailable" }
                check(effect.setDataCaptureListener(input, maxRate, true, false) == Visualizer.SUCCESS) {
                    "Visualizer waveform listener rejected"
                }
                effect.enabled = true
                return input
            } catch (failure: Throwable) {
                try { effect.enabled = false } catch (_: Throwable) {}
                try { effect.release() } catch (_: Throwable) {}
                throw failure
            }
        }
    }

    override fun onWaveFormDataCapture(source: Visualizer, waveform: ByteArray, samplingRate: Int) {
        if (source !== visualizer || waveform.isEmpty() ||
            samplingRate !in 8_000_000..192_000_000) return
        synchronized(lock) {
            if (closed) return
            if (sourceRate != samplingRate) {
                // A source format switch cannot splice unlike audio clocks.
                sourceRate = samplingRate
                first = 0
                count = 0
                nextPosition = 0.0
            }
            // Android supplies samplingRate in milliHertz, waveform as unsigned
            // 8-bit mono. These are intermittent visualization windows, not a
            // continuous high-fidelity reproduction of the original stream.
            val step = samplingRate.toDouble() / (1000.0 * outputRate)
            var position = nextPosition
            while (position < waveform.size.toDouble()) {
                val unsigned = waveform[position.toInt()].toInt() and 0xff
                val pcm = ((unsigned - 128) shl 8).toShort()
                if (count == ring.size) {
                    first = (first + 1) % ring.size
                    count--
                }
                ring[(first + count) % ring.size] = pcm
                count++
                position += step
            }
            nextPosition = position - waveform.size.toDouble()
        }
    }

    override fun onFftDataCapture(source: Visualizer, fft: ByteArray, samplingRate: Int) {
        // One shared PCM/FFT analyser; never combine an unrelated OEM FFT with it.
    }

    override fun read(buffer: ShortArray): Int {
        require(buffer.isNotEmpty())
        synchronized(lock) {
            check(!closed) { "Visualizer source already closed" }
            val size = minOf(buffer.size, count)
            for (i in 0 until size) buffer[i] = ring[(first + i) % ring.size]
            first = (first + size) % ring.size
            count -= size
            return size
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            first = 0
            count = 0
            sourceRate = 0
            nextPosition = 0.0
        }
        // An in-flight Android callback is fenced by closed, and the native
        // effect must always be released even if Android refuses disable.
        try {
            visualizer.enabled = false
        } catch (_: Throwable) {
        } finally {
            try { visualizer.release() } catch (_: Throwable) {}
        }
    }
}
