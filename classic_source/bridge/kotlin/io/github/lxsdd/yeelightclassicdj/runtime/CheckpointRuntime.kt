package io.github.lxsdd.yeelightclassicdj.runtime

import android.content.Context
import android.os.SystemClock
import loftdj.classic.ClassicAndroidAudioRecordInput
import loftdj.classic.ClassicDjEngine
import loftdj.classic.ClassicMusicCoordinator
import loftdj.classic.ClassicPcmCaptureSession
import loftdj.classic.ClassicPcmDjBridge

/**
 * Concrete private Classic/Loft-DJ adapter, binding the *existing* tested DSP checkpoint.
 *
 * IMPORTANT: this iteration has no authenticated, hardware-tested reverse Music transport.
 * It intentionally registers ZERO bulbs. Its deny-only Transport makes microphone/FFT/BPM
 * demonstrable on Android without making a single LAN, cloud, or device write.
 * A separate capability-/ownership-gated transport+selection implementation is required
 * before lighting output. No fallback to ordinary RGB RPC is ever permitted.
 */
class CheckpointRuntime : DjRuntime {
    private val lock = Any()
    private var state = State.NEW
    private var worker: Thread? = null
    private var capture: ClassicPcmCaptureSession? = null
    private var engine: ClassicDjEngine? = null
    private var listener: DjRuntime.Listener? = null
    private var generation = 0L

    private enum class State { NEW, STARTING, RUNNING, STOPPING, CLOSED }

    /** For JVM-only deterministic regression tests; never a production remote adapter. */
    internal var inputFactory: () -> ClassicPcmCaptureSession.Input = {
        ClassicAndroidAudioRecordInput.open(sampleRate = SAMPLE_RATE)
    }
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var idle: () -> Unit = { Thread.sleep(12L) }

    override fun start(context: Context, source: String, listener: DjRuntime.Listener) {
        require(source == "microphone") { "Only explicit microphone capture is supported" }
        synchronized(lock) {
            check(state == State.NEW) { "Session cannot be restarted" }
            state = State.STARTING
            this.listener = listener
        }
        var createdInput: ClassicPcmCaptureSession.Input? = null
        try {
            createdInput = inputFactory()
            val coordinator = ClassicMusicCoordinator(DENY_ALL_LAMP_TRANSPORT)
            // No devices registered: there is no way to issue LAN/Cloud/Music commands.
            val newEngine = ClassicDjEngine(coordinator)
            val bridge = ClassicPcmDjBridge(newEngine, sampleRate = SAMPLE_RATE)
            val newCapture = ClassicPcmCaptureSession(bridge, createdInput, clock) { output ->
                val current = synchronized(lock) {
                    if (state == State.RUNNING) this.listener else null
                }
                if (current != null) current.onBeat(output.bpm ?: 0.0, output.level.toFloat())
            }
            newCapture.start()
            synchronized(lock) {
                check(state == State.STARTING) { "Stop requested during startup" }
                capture = newCapture
                engine = newEngine
                state = State.RUNNING
                val mine = ++generation
                worker = Thread({ readLoop(mine) }, "Classic-DJ-PCM").also {
                    it.isDaemon = true
                    it.start()
                }
            }
            listener.onState("MIC_ANALYSIS_ONLY_NO_LAMPS")
        } catch (error: Throwable) {
            synchronized(lock) { state = State.CLOSED; listener.let { this.listener = null } }
            try { createdInput?.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        }
    }

    private fun readLoop(mine: Long) {
        try {
            while (true) {
                val session = synchronized(lock) {
                    if (state == State.RUNNING && mine == generation) capture else null
                } ?: break
                val received = session.poll(bufferSize = WINDOW_SIZE)
                if (!received) idle()
            }
        } catch (error: Throwable) {
            val callback = synchronized(lock) {
                if (state == State.RUNNING && mine == generation) listener else null
            }
            try { callback?.onError("Microphone capture failed; device commands remain disabled") }
            finally { stop() }
        }
    }

    override fun stop() {
        val session: ClassicPcmCaptureSession?
        val localWorker: Thread?
        val localEngine: ClassicDjEngine?
        val callback: DjRuntime.Listener?
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return
            state = State.STOPPING
            ++generation // fence all late read-loop callbacks
            session = capture; capture = null
            localEngine = engine; engine = null
            localWorker = worker; worker = null
            callback = listener; listener = null
        }
        try {
            // Source owned by this instance; never touches an OEM/foreign music session.
            session?.close()
            localEngine?.stop()
            if (localWorker != null && localWorker !== Thread.currentThread()) {
                localWorker.join(1200L)
            }
        } finally {
            synchronized(lock) { state = State.CLOSED }
            callback?.onState("STOPPED")
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 1024
        val DENY_ALL_LAMP_TRANSPORT = object : ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = null
            override fun requestStart(id: String) = false
            override fun reverseConnected(id: String) = false
            override fun requestStop(id: String) = false
            override fun closeReverse(id: String) = Unit
            override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int) = false
        }
    }
}
