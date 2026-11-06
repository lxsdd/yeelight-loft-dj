package loftdj.classic

/** Capture lifecycle is explicit; this JVM-safe adapter performs no Android permission or network actions. */
class ClassicPcmCaptureSession(
    private val bridge: ClassicPcmDjBridge,
    private val input: Input,
    private val clock: () -> Long,
    private val onOutput: (ClassicDjEngine.Output) -> Unit = {}
) {
    interface Input {
        /** Implementations must return 1..buffer.size, 0 for no data, or negative for failure. */
        fun read(buffer: ShortArray): Int
        fun close()
    }
    enum class State { NEW, RUNNING, STOPPED, FAILED, CLOSED }
    private var state = State.NEW
    private var lastTimestamp = -1L
    private var pending = ShortArray(0)
    private var pendingCount = 0
    @Synchronized fun state() = state
    @Synchronized fun start() {
        check(state == State.NEW) { "Capture cannot be restarted after stop/failure" }
        state = State.RUNNING
    }
    /** One bounded read per call. No hidden thread, automatic retry, or device command. */
    @Synchronized fun poll(bufferSize: Int = 1024, gain: Double = 1.0): Boolean {
        check(state == State.RUNNING)
        require(bufferSize in 64..8192 && bufferSize.countOneBits() == 1)
        require(gain.isFinite() && gain in 0.1..10.0)
        try {
            if (pending.size != bufferSize) {
                check(pendingCount == 0) { "Cannot change window size mid-frame" }
                pending = ShortArray(bufferSize)
            }
            val chunk = ShortArray(bufferSize - pendingCount)
            val n = input.read(chunk)
            if (n < 0 || n > chunk.size) error("Invalid capture read count")
            if (n == 0) return false
            // Preserve partial PCM without padding or reusing stale samples.
            chunk.copyInto(pending, pendingCount, 0, n)
            pendingCount += n
            if (pendingCount != bufferSize) return false
            pendingCount = 0
            val now = clock()
            if (now < 0 || now <= lastTimestamp) return false
            lastTimestamp = now
            onOutput(bridge.accept(now, pending.copyOf(), gain))
            return true
        } catch (t: Throwable) {
            state = State.FAILED
            try { input.close() } catch (close: Throwable) { t.addSuppressed(close) }
            throw t
        }
    }
    @Synchronized fun stop() {
        if (state == State.CLOSED || state == State.STOPPED) return
        val alreadyClosed = state == State.FAILED
        state = State.STOPPED
        if (!alreadyClosed) input.close()
    }
    @Synchronized fun close() {
        if (state != State.CLOSED) {
            val alreadyClosed = state == State.FAILED || state == State.STOPPED
            state = State.CLOSED
            if (!alreadyClosed) input.close()
        }
    }
}
