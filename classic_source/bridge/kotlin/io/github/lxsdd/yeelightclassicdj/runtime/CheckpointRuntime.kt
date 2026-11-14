package io.github.lxsdd.yeelightclassicdj.runtime

import android.content.Context
import android.os.SystemClock
import loftdj.classic.ClassicAndroidAudioRecordInput
import loftdj.classic.ClassicDjEngine
import loftdj.classic.ClassicMusicCoordinator
import loftdj.classic.ClassicPulseScene
import loftdj.classic.ClassicPcmCaptureSession
import loftdj.classic.ClassicPcmDjBridge
import loftdj.classic.ClassicYeelightMusicTransport

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
    private var inputBridge: ClassicPcmDjBridge? = null
    private var sensitivity = 1.0
    private var scene = "SPECTRUM"
    private var pulse = ClassicPulseScene.Options()
    private var listener: DjRuntime.Listener? = null
    private var generation = 0L

    private enum class State { NEW, STARTING, RUNNING, STOPPING, CLOSED }

    /** For JVM-only deterministic regression tests; never a production remote adapter. */
    internal var inputFactory: () -> ClassicPcmCaptureSession.Input = {
        ClassicAndroidAudioRecordInput.open(sampleRate = SAMPLE_RATE)
    }
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var idle: () -> Unit = { Thread.sleep(12L) }
    /** Injected by deterministic JVM tests. Production creates REAL private-LAN reverse sockets only after user selection. */
    internal var wifiAddress: () -> String? = { ClassicYeelightMusicTransport.wifiPrivateIpv4() }
    internal var transportFactory: (String, Set<String>) -> ClassicMusicCoordinator.Transport =
        { ip, ids -> ClassicYeelightMusicTransport(ip, ids) }

    /** State-only in-place reconfiguration. No network, new worker or re-handshake. */
    override fun configure(sensitivity: Double, scene: String): Boolean {
        if (!sensitivity.isFinite() || sensitivity !in 0.25..4.0 ||
            scene !in setOf("SPECTRUM", "AMBIENT", "BEAT", "PULSE")) return false
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return false
            inputBridge?.setSensitivity(sensitivity)
            engine?.setScene(scene)
            this.sensitivity = sensitivity
            this.scene = scene
            return true
        }
    }

    override fun configurePulse(
        rgb: Int, beatSync: Boolean, bpm: Int, minimum: Int, maximum: Int,
        attackMs: Int, decayMs: Int, gamma: Double
    ): Boolean {
        val proposed = runCatching {
            ClassicPulseScene.Options(rgb, beatSync, bpm, minimum, maximum, attackMs, decayMs, gamma)
        }.getOrNull() ?: return false
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return false
            if (engine?.configurePulse(proposed) == false) return false
            pulse = proposed
            return true
        }
    }

    override fun start(context: Context, source: String, selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        require(source == "microphone") { "Only explicit microphone capture is supported" }
        require(selectedLampIps.size <= 4 && selectedLampIps.distinct().size == selectedLampIps.size)
        require(selectedLampIps.all(ClassicYeelightMusicTransport::validLanIp))
        synchronized(lock) {
            check(state == State.NEW) { "Session cannot be restarted" }
            state = State.STARTING
            this.listener = listener
        }
        var createdInput: ClassicPcmCaptureSession.Input? = null
        var stagedEngine: ClassicDjEngine? = null
        try {
            val localIp = if (selectedLampIps.isEmpty()) null else
                wifiAddress()
                    ?: error("Wi-Fi IPv4 address not found; no device commands sent")
            val coordinator = if (selectedLampIps.isEmpty()) ClassicMusicCoordinator(DENY_ALL_LAMP_TRANSPORT)
                              else ClassicMusicCoordinator(transportFactory(localIp!!, selectedLampIps.toSet()))
            for (ip in selectedLampIps) coordinator.register(ip, musicSupported = true)
            val newEngine = ClassicDjEngine(coordinator)
            stagedEngine = newEngine
            // The user manually chooses participants; NOTHING starts by discovery/notification.
            // A STOP during a previous device handshake must not start more lamps.
            // The staged engine still cleans up any confirmed OWNED socket on abort.
            val states = if (selectedLampIps.isEmpty()) emptyMap() else
                newEngine.select(selectedLampIps) {
                    synchronized(lock) { state == State.STARTING }
                }
            val owned = states.filterValues { it == ClassicMusicCoordinator.State.OWNED }.keys
            if (selectedLampIps.isNotEmpty() && owned.isEmpty()) {
                error("No confirmed reverse channel; leave remote state untouched and stop")
            }
            synchronized(lock) { check(state == State.STARTING) { "Session stopped during setup" } }
            createdInput = inputFactory()
            val bridge = ClassicPcmDjBridge(newEngine, sampleRate = SAMPLE_RATE, automaticAnalysisGain = true)
            val newCapture = ClassicPcmCaptureSession(bridge, createdInput, clock) { output ->
                val current = synchronized(lock) {
                    if (state == State.RUNNING) this.listener else null
                }
                current?.onBeat(output.bpm ?: 0.0, output.level.toFloat())
            }
            newCapture.start()
            synchronized(lock) {
                check(state == State.STARTING) { "Stop requested during startup" }
                // Changes received while the network handshake was pending
                // apply before the very first live PCM frame.
                check(newEngine.configurePulse(pulse))
                check(newEngine.setScene(scene))
                check(bridge.setSensitivity(sensitivity))
                capture = newCapture
                inputBridge = bridge
                engine = newEngine
                state = State.RUNNING
                val mine = ++generation
                worker = Thread({ readLoop(mine) }, "Classic-DJ-PCM").also {
                    it.isDaemon = true
                    it.start()
                }
            }
            listener.onState(if (owned.isEmpty()) "MIC_ANALYSIS_ONLY_NO_LAMPS"
                             else "DJ_ACTIVE_${owned.size}_LAMPS")
        } catch (error: Throwable) {
            // Canceling during a network handshake must not leave an owned music session.
            try { stagedEngine?.stop() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            synchronized(lock) { state = State.CLOSED; this.listener = null }
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
            inputBridge = null
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
