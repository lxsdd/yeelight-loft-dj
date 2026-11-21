package android.media.audiofx

/** Small deterministic SDK stand-in; never included in an Android application. */
class Visualizer(val sessionId: Int) {
    interface OnDataCaptureListener {
        fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int)
        fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int)
    }

    companion object {
        const val SUCCESS = 0
        var last: Visualizer? = null
        var constructed = 0
        var released = 0
        var rejectedConstructors = false
        var rejectedSizes = false
        var rejectedListeners = false
        var rejectedEnabling = false
        var minSize = 128
        var maxSize = 1024
        var callbackRate = 20_000
        fun getCaptureSizeRange(): IntArray = intArrayOf(minSize, maxSize)
        fun getMaxCaptureRate() = callbackRate
        fun reset() {
            last = null; constructed = 0; released = 0
            rejectedConstructors = false; rejectedSizes = false
            rejectedListeners = false; rejectedEnabling = false
            minSize = 128; maxSize = 1024; callbackRate = 20_000
        }
    }

    init {
        if (rejectedConstructors) throw SecurityException("synthetic Visualizer deny")
        constructed++
        last = this
    }
    var listener: OnDataCaptureListener? = null
        private set
    var enabled: Boolean = false
        set(value) {
            if (value && rejectedEnabling) throw IllegalStateException("synthetic enable deny")
            field = value
        }
    var captureSize = 0
        private set
    var releasedOnce = false
        private set
    fun setCaptureSize(size: Int): Int {
        if (rejectedSizes) return -1
        captureSize = size
        return SUCCESS
    }
    fun setDataCaptureListener(observer: OnDataCaptureListener?, rate: Int,
                               waveform: Boolean, fft: Boolean): Int {
        if (rejectedListeners || rate <= 0 || !waveform || fft) return -1
        listener = observer
        return SUCCESS
    }
    fun emit(bytes: ByteArray, samplingRateMilliHz: Int) {
        listener?.onWaveFormDataCapture(this, bytes, samplingRateMilliHz)
    }
    fun release() {
        if (releasedOnce) return
        releasedOnce = true
        enabled = false
        released++
    }
}
