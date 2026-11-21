package io.github.lxsdd.yeelightclassicdj.runtime

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import loftdj.classic.ClassicAndroidAudioRecordInput
import loftdj.classic.ClassicAndroidVisualizerInput
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
    private var clockOnly = false
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
    /** Verifiable original Classic session only; no guessed 0/mix capture. */
    internal var visualizerFactory: (Int) -> ClassicPcmCaptureSession.Input = {
        sessionId -> ClassicAndroidVisualizerInput.open(sessionId, sampleRate = SAMPLE_RATE)
    }
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }
    internal var idle: () -> Unit = { Thread.sleep(12L) }
    /** Injected by deterministic JVM tests. Production creates REAL private-LAN reverse sockets only after user selection. */
    internal var wifiAddress: () -> String? = { ClassicYeelightMusicTransport.wifiPrivateIpv4() }
    internal var transportFactory: (String, Set<String>) -> ClassicMusicCoordinator.Transport =
        { ip, ids -> ClassicYeelightMusicTransport(ip, ids) }

    /** A scene can use wall-clock time only if it has no music dependency. */
    private fun clockSceneAllowed(name: String, settings: ClassicPulseScene.Options): Boolean =
        name == "SCANNER" || name == "METEOR" || name == "WAVE" ||
        (name == "PULSE" && !settings.beatSync)

    /** State-only in-place reconfiguration. No network, new worker or re-handshake. */
    override fun configure(sensitivity: Double, scene: String): Boolean {
        if (!sensitivity.isFinite() || sensitivity !in 0.25..4.0 ||
            scene !in setOf("SPECTRUM", "AMBIENT", "BEAT", "PULSE",
                            "SCANNER", "METEOR", "WAVE")) return false
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return false
            if (clockOnly && !clockSceneAllowed(scene, pulse)) return false
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
        // Avoid Kotlin Result.Companion field references. The original Classic
        // APK supplies an older Kotlin runtime, and the private clone excludes
        // duplicate stdlib classes. An inline runCatching block compiles to
        // getstatic kotlin.Result.Companion and can crash with NoSuchFieldError
        // before the microphone session even begins.
        val proposed = try {
            ClassicPulseScene.Options(rgb, beatSync, bpm, minimum, maximum, attackMs, decayMs, gamma)
        } catch (invalid: IllegalArgumentException) {
            // ClassicPulseScene.Options uses require() for invalid user values.
            // Do not swallow LinkageError or other unexpected runtime failures.
            return false
        }
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return false
            if (clockOnly && scene == "PULSE" && proposed.beatSync) return false
            if (engine?.configurePulse(proposed) == false) return false
            pulse = proposed
            return true
        }
    }

    override fun start(context: Context, source: String, selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        require(source == "microphone") { "Unapproved microphone source" }
        startCapture(selectedLampIps, listener) { inputFactory() }
    }

    override fun startInternal(context: Context, projection: MediaProjection,
                               selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        startCapture(selectedLampIps, listener) {
            ClassicPlaybackCaptureInput.open(projection, sampleRate = SAMPLE_RATE)
        }
    }

    /** The original OEM Music source must verify the concrete session ID
     * before exposing this entry point. This does not start a second music UI.
     */
    override fun startVisualizer(context: Context, audioSessionId: Int,
                                 selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        require(audioSessionId >= 0) { "Unverified original audio-session ID" }
        startCapture(selectedLampIps, listener) { visualizerFactory(audioSessionId) }
    }

    /** Scene-gallery backend: shares the owned-only, paced lighting engine.
     * Not exposed by the OEM UI until its participant identity is verified.
     * No PCM or foreground-audio service is needed for this call.
     */
    override fun startTimedScene(context: Context, selectedLampIps: List<String>,
                                 listener: DjRuntime.Listener) {
        require(selectedLampIps.isNotEmpty()) { "No verified scene participants" }
        startCapture(selectedLampIps, listener, sourceInput = null)
    }

    private fun startCapture(selectedLampIps: List<String>, listener: DjRuntime.Listener,
                             sourceInput: (() -> ClassicPcmCaptureSession.Input)?) {
        require(selectedLampIps.size <= 4 && selectedLampIps.distinct().size == selectedLampIps.size)
        require(selectedLampIps.all(ClassicYeelightMusicTransport::validLanIp))
        val independentScene = sourceInput == null
        synchronized(lock) {
            check(state == State.NEW) { "Session cannot be restarted" }
            if (independentScene) check(clockSceneAllowed(scene, pulse)) {
                "Audio-dependent effects require live audio"
            }
            state = State.STARTING
            this.listener = listener
        }
        var createdInput: ClassicPcmCaptureSession.Input? = null
        var stagedEngine: ClassicDjEngine? = null
        try {
            // Only the physical Android source-open phase receives this typed
            // diagnostic. The original failure remains the cause for private QA,
            // but no device/audio/OEM text crosses the user-visible status path.
            createdInput = try {
                sourceInput?.invoke()
            } catch (failure: Throwable) {
                val kind = when (failure) {
                    is SecurityException -> DjRuntime.CaptureFailureKind.PERMISSION
                    is LinkageError -> DjRuntime.CaptureFailureKind.ABI
                    is IllegalArgumentException -> DjRuntime.CaptureFailureKind.FORMAT
                    is IllegalStateException -> DjRuntime.CaptureFailureKind.STATE
                    else -> DjRuntime.CaptureFailureKind.UNKNOWN
                }
                throw DjRuntime.CaptureStartFailure(kind, failure)
            }
            // Never negotiate or power any lamp before the physical source
            // is successfully opened and this invocation still owns STARTING.
            synchronized(lock) { check(state == State.STARTING) { "Source cancelled before lamp handshake" } }
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
            val bridge = if (independentScene) null else
                ClassicPcmDjBridge(newEngine, sampleRate = SAMPLE_RATE, automaticAnalysisGain = true)
            val newCapture = if (bridge == null) null else
                ClassicPcmCaptureSession(bridge, checkNotNull(createdInput), clock) { output ->
                val current = synchronized(lock) {
                    if (state == State.RUNNING) this.listener else null
                }
                current?.onBeat(output.bpm ?: 0.0, output.level.toFloat())
                current?.onSpectrum(output.bass.toFloat(), output.mid.toFloat(),
                    output.presence.toFloat(), output.treble.toFloat(),
                    output.beat, output.confidence.toFloat())
            }
            newCapture?.start()
            synchronized(lock) {
                check(state == State.STARTING) { "Stop requested during startup" }
                // Changes received while the network handshake was pending
                // apply before the very first live PCM frame.
                check(newEngine.configurePulse(pulse))
                check(newEngine.setScene(scene))
                check(bridge?.setSensitivity(sensitivity) ?: true)
                capture = newCapture
                inputBridge = bridge
                clockOnly = independentScene
                engine = newEngine
                state = State.RUNNING
                val mine = ++generation
                worker = Thread({ readLoop(mine) }, "Classic-DJ-PCM").also {
                    it.isDaemon = true
                    it.start()
                }
            }
            listener.onState(if (independentScene) "SCENE_TIMED_" + owned.size + "_LAMPS"
                             else if (owned.isEmpty()) "MIC_ANALYSIS_ONLY_NO_LAMPS"
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
                if (!synchronized(lock) { state == State.RUNNING && mine == generation }) break
                val session = synchronized(lock) { capture }
                val received = session?.poll(bufferSize = WINDOW_SIZE) ?: false
                if (!received) {
                    // Scanner, meteor, wave and timer-pulse keep moving when a
                    // valid capture temporarily yields no PCM. The same owned
                    // engine/rate limiter is used; NO new source or Music socket.
                    val current = synchronized(lock) {
                        if (state == State.RUNNING && mine == generation) engine else null
                    }
                    current?.clockTick(clock())
                    idle()
                }
            }
        } catch (error: Throwable) {
            val callback = synchronized(lock) {
                if (state == State.RUNNING && mine == generation) listener else null
            }
            try { callback?.onError("Audio/scene runtime stopped safely") }
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
            clockOnly = false
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
