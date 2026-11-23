package io.github.lxsdd.yeelightclassicdj.runtime

import android.content.Context
import android.media.projection.MediaProjection
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import loftdj.classic.ClassicAndroidAudioRecordInput
import loftdj.classic.ClassicAndroidVisualizerInput
import loftdj.classic.ClassicDjEngine
import loftdj.classic.ClassicMusicCoordinator
import loftdj.classic.ClassicPulseScene
import loftdj.classic.ClassicSpatialChoreography
import loftdj.classic.ClassicPcmCaptureSession
import loftdj.classic.ClassicPcmDjBridge
import loftdj.classic.ClassicYeelightMusicTransport
import io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalMusicAudioSignal

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
    // Serialize all stop callers, including the runtime worker and Activity.
    // Returning early from a concurrent STOP would release the UI output lease
    // while owned reverse sockets were still being closed.
    private val stopSerial = Any()
    private var startFinished: CountDownLatch? = null
    private var startThread: Thread? = null
    private var state = State.NEW
    private var worker: Thread? = null
    private var capture: ClassicPcmCaptureSession? = null
    private var engine: ClassicDjEngine? = null
    private var inputBridge: ClassicPcmDjBridge? = null
    private var clockOnly = false
    private var originalCallbackOwner = false
    private var originalOutputGuard: DjRuntime.ConsentGuard? = null
    // A failed reverse write/STOP may leave a remote music_on state unknown.
    // Once uncertain, all later stop callers must fail closed instead of
    // releasing the process-wide UI output admission fence.
    private var remoteUncertain = false
    private var shutdownConfirmed = true
    private data class OriginalFrame(
        val timestampMs: Long, val bands: DoubleArray, val beat: Boolean,
        val bpm: Double, val confidence: Double
    )
    // One last-value mailbox. The ORIGINAL audio callback never waits for
    // network I/O, spawns a recorder or accumulates unbounded PCM/FFT frames.
    private var pendingOriginal: OriginalFrame? = null
    private var sensitivity = 1.0
    private var scene = "SPECTRUM"
    private var pulse = ClassicPulseScene.Options()
    private var spatialSettings = ClassicSpatialChoreography.Options()
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

    /** Safe live scene-editor parameters. No device, capture or socket acquisition. */
    override fun configureSpatial(rgb: Int, speedPercent: Int, reverse: Boolean,
                                  trailLength: Int, minBrightness: Int,
                                  maxBrightness: Int): Boolean {
        val proposed = try {
            ClassicSpatialChoreography.Options(rgb, speedPercent, reverse, trailLength,
                                               minBrightness, maxBrightness)
        } catch (invalid: IllegalArgumentException) { return false }
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED) return false
            if (engine?.configureSpatial(rgb, speedPercent, reverse, trailLength,
                                         minBrightness, maxBrightness) == false) return false
            spatialSettings = proposed
            return true
        }
    }

    /**
     * A rejected scene-editor update must not change *any* existing pulse,
     * geometry, gain or mode. Validate all fields before touching the engine.
     * The engine's individual setters accept every prevalidated proposal and
     * this lock serializes the update with ongoing capture/clock callbacks.
     */
    override fun applySceneSettings(settings: DjRuntime.SceneSettings): Boolean {
        if (!settings.sensitivity.isFinite() || settings.sensitivity !in 0.25..4.0 ||
            settings.scene !in setOf("SPECTRUM", "AMBIENT", "BEAT", "PULSE",
                                    "SCANNER", "METEOR", "WAVE")) return false
        val nextPulse = try {
            ClassicPulseScene.Options(settings.pulseRgb, settings.beatSync,
                settings.pulseBpm, settings.pulseMin, settings.pulseMax,
                settings.attackMs, settings.decayMs, settings.gamma)
        } catch (invalid: IllegalArgumentException) { return false }
        val nextSpatial = try {
            ClassicSpatialChoreography.Options(settings.spatialRgb, settings.spatialSpeed,
                settings.reverse, settings.trailLength, settings.spatialMin, settings.spatialMax,
                settings.spatialRainbow)
        } catch (invalid: IllegalArgumentException) { return false }
        synchronized(lock) {
            if (state == State.STOPPING || state == State.CLOSED ||
                (clockOnly && !clockSceneAllowed(settings.scene, nextPulse))) return false
            val activeEngine = engine
            if (activeEngine != null) {
                if (!activeEngine.configurePulse(nextPulse)) return false
                if (!activeEngine.configureSpatial(nextSpatial.rgb, nextSpatial.speedPercent,
                        nextSpatial.reverse, nextSpatial.trailLength,
                        nextSpatial.minBrightness, nextSpatial.maxBrightness,
                        nextSpatial.rainbow)) return false
                if (!activeEngine.setScene(settings.scene)) return false
            }
            if (inputBridge?.setSensitivity(settings.sensitivity) == false) return false
            sensitivity = settings.sensitivity
            scene = settings.scene
            pulse = nextPulse
            spatialSettings = nextSpatial
            return true
        }
    }

    override fun start(context: Context, source: String, selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        require(source == "microphone") { "Unapproved microphone source" }
        startCapture(selectedLampIps, listener, sourceInput = { inputFactory() })
    }

    override fun startInternal(context: Context, projection: MediaProjection,
                               selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        startCapture(selectedLampIps, listener, sourceInput = {
            ClassicPlaybackCaptureInput.open(projection, sampleRate = SAMPLE_RATE)
        })
    }

    /** The original OEM Music source must verify the concrete session ID
     * before exposing this entry point. This does not start a second music UI.
     */
    override fun startVisualizer(context: Context, audioSessionId: Int,
                                 selectedLampIps: List<String>, listener: DjRuntime.Listener) {
        require(audioSessionId >= 0) { "Unverified original audio-session ID" }
        startCapture(selectedLampIps, listener, sourceInput = { visualizerFactory(audioSessionId) })
    }

    /**
     * Original Classic player owns capture and Play/Pause. Start ONLY the
     * already-tested owned reverse Music output; do not create AudioRecord,
     * Visualizer, MediaProjection or an independent audio service.
     * Caller must supply a separately verified, consented OEM-ID -> LAN-IP
     * snapshot, never naked OEM keys.
     */
    fun startOriginalAudio(selectedLampIps: List<String>, listener: DjRuntime.Listener,
                           consent: DjRuntime.ConsentGuard) {
        require(selectedLampIps.isNotEmpty()) { "Original source requires confirmed endpoints" }
        check(consent.isStillAuthorized()) { "Original owner or selected cohort revoked" }
        startCapture(selectedLampIps, listener, sourceInput = null,
                     originalCallbacks = true, originalAuthorization = consent)
    }

    /** Original Classic time-based scenes share the SAME explicit participant
     * grant, cancellation guard, owned Music transport and stop sequence as
     * original audio. No AudioRecord, Visualizer or MediaProjection is opened.
     * The original SceneFragment's selected settings determine clock eligibility.
     */
    fun startOriginalTimedScene(selectedLampIps: List<String>,
                                listener: DjRuntime.Listener,
                                consent: DjRuntime.ConsentGuard) {
        require(selectedLampIps.isNotEmpty()) { "No verified original timed participants" }
        check(consent.isStillAuthorized()) { "Original timed scene selection revoked" }
        startCapture(selectedLampIps, listener, sourceInput = null,
                     originalAuthorization = consent)
    }

    /** Nonblocking bounded frame handoff from the genuine OEM DSP monitor.
     * A no-owner/stale/closed session cannot enqueue work or touch any peer.
     */
    fun submitOriginalFrame(frame: ClassicOriginalMusicAudioSignal.Frame?): Boolean {
        if (frame == null || frame.bands == null || frame.bands.size != 4 ||
            !frame.bands.all { it.isFinite() && it in 0f..1f } ||
            !frame.bpm.isFinite() || frame.bpm !in 0f..240f ||
            !frame.confidence.isFinite() || frame.confidence !in 0f..1f ||
            !frame.level.isFinite() || frame.level !in 0f..1f) return false
        synchronized(lock) {
            if (state != State.RUNNING || !originalCallbackOwner || engine == null ||
                originalOutputGuard?.isStillAuthorized() != true)
                return false
            pendingOriginal = OriginalFrame(clock(),
                DoubleArray(4) { frame.bands[it].toDouble() },
                frame.beat, frame.bpm.toDouble(), frame.confidence.toDouble())
            return true
        }
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
                             sourceInput: (() -> ClassicPcmCaptureSession.Input)?,
                             originalCallbacks: Boolean = false,
                             originalAuthorization: DjRuntime.ConsentGuard? = null) {
        // Every current and subsequently added explicitly selected lamp is eligible.
        // Validation is per target; no arbitrary 4/14/other fleet-size ceiling.
        require(selectedLampIps.distinct().size == selectedLampIps.size)
        require(selectedLampIps.all(ClassicYeelightMusicTransport::validLanIp))
        val independentScene = sourceInput == null && !originalCallbacks
        val thisStart = CountDownLatch(1)
        synchronized(lock) {
            check(state == State.NEW) { "Session cannot be restarted" }
            if (independentScene) check(clockSceneAllowed(scene, pulse)) {
                "Audio-dependent effects require live audio"
            }
            if (originalAuthorization != null)
                check(originalAuthorization.isStillAuthorized()) {
                    "Original output consent missing or revoked"
                }
            state = State.STARTING
            startFinished = thisStart
            startThread = Thread.currentThread()
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
                    synchronized(lock) { state == State.STARTING } &&
                        (originalAuthorization?.isStillAuthorized() ?: true)
                }
            if (states.values.any { it == ClassicMusicCoordinator.State.QUARANTINED })
                synchronized(lock) { remoteUncertain = true; shutdownConfirmed = false }
            val owned = states.filterValues { it == ClassicMusicCoordinator.State.OWNED }.keys
            if (selectedLampIps.isNotEmpty() && owned.isEmpty()) {
                error("No confirmed reverse channel; leave remote state untouched and stop")
            }
            // All explicitly selected start paths (original audio, original
            // timed, independent scene, microphone/Visualizer) require the
            // ENTIRE confirmed cohort. A foreign, unavailable or cancelled
            // peer revokes the proposal. Never silently light a subset.
            // The catch/finally rollback stops only already OWNED peers.
            if (selectedLampIps.isNotEmpty() && owned.size != selectedLampIps.size)
                error("Selected Music cohort only partially owned")
            synchronized(lock) {
                check(state == State.STARTING) { "Session stopped during setup" }
                if (originalAuthorization != null)
                    check(originalAuthorization.isStillAuthorized()) {
                        "Original output consent revoked during handshake"
                    }
            }
            val bridge = if (sourceInput == null) null else
                ClassicPcmDjBridge(newEngine, sampleRate = SAMPLE_RATE, automaticAnalysisGain = true)
            val newCapture = if (bridge == null) null else
                ClassicPcmCaptureSession(bridge, checkNotNull(createdInput), clock) { output ->
                // The engine has already stopped confirmed surviving peers.
                // Closing this worker also closes PCM and reports the failure.
                if (output.failed.isNotEmpty()) {
                    synchronized(lock) { remoteUncertain = true; shutdownConfirmed = false }
                    error("Owned music output lost")
                }
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
                check(newEngine.configureSpatial(spatialSettings.rgb, spatialSettings.speedPercent,
                    spatialSettings.reverse, spatialSettings.trailLength,
                    spatialSettings.minBrightness, spatialSettings.maxBrightness,
                    spatialSettings.rainbow))
                check(newEngine.setScene(scene))
                check(bridge?.setSensitivity(sensitivity) ?: true)
                capture = newCapture
                inputBridge = bridge
                clockOnly = independentScene
                originalCallbackOwner = originalCallbacks
                originalOutputGuard = originalAuthorization
                pendingOriginal = null
                engine = newEngine
                state = State.RUNNING
                val mine = ++generation
                worker = Thread({ readLoop(mine) }, "Classic-DJ-PCM").also {
                    it.isDaemon = true
                    it.start()
                }
            }
            listener.onState(if (independentScene) "SCENE_TIMED_" + owned.size + "_LAMPS"
                             else if (originalCallbacks) "ORIGINAL_AUDIO_${owned.size}_LAMPS"
                             else if (owned.isEmpty()) "MIC_ANALYSIS_ONLY_NO_LAMPS"
                             else "DJ_ACTIVE_${owned.size}_LAMPS")
        } catch (error: Throwable) {
            // Canceling during a network handshake must not leave an owned music session.
            try {
                val cleanup = stagedEngine?.stop()
                if (stagedEngine?.uncertainOutput() == true ||
                    cleanup?.values?.any { it != ClassicMusicCoordinator.State.IDLE } == true)
                    synchronized(lock) { remoteUncertain = true }
            } catch (cleanup: Throwable) {
                synchronized(lock) { remoteUncertain = true }
                error.addSuppressed(cleanup)
            }
            synchronized(lock) {
                state = State.CLOSED
                this.listener = null
                if (remoteUncertain) shutdownConfirmed = false
            }
            try { createdInput?.close() } catch (close: Throwable) { error.addSuppressed(close) }
            throw error
        } finally {
            // A concurrently requested STOP must wait until a partially
            // negotiated Music group has either been committed or rolled back.
            synchronized(lock) {
                if (startFinished === thisStart) {
                    startFinished = null
                    startThread = null
                }
            }
            thisStart.countDown()
        }
    }

    private fun readLoop(mine: Long) {
        try {
            while (true) {
                if (!synchronized(lock) { state == State.RUNNING && mine == generation }) break
                val consent = synchronized(lock) { originalOutputGuard }
                if (consent != null && !consent.isStillAuthorized())
                    error("Original output owner revoked")
                val session = synchronized(lock) { capture }
                val received = session?.poll(bufferSize = WINDOW_SIZE) ?: false
                if (consent != null && !consent.isStillAuthorized())
                    error("Original output owner revoked after audio poll")
                val original = synchronized(lock) {
                    if (state == State.RUNNING && mine == generation && originalCallbackOwner) {
                        val next = pendingOriginal
                        pendingOriginal = null
                        next
                    } else null
                }
                if (original != null) {
                    val current = synchronized(lock) {
                        if (state == State.RUNNING && mine == generation) engine else null
                    }
                    val output = current?.originalFrame(original.timestampMs,
                        original.bands, original.beat, original.bpm, original.confidence)
                    if (output != null) {
                        if (output.failed.isNotEmpty()) {
                            synchronized(lock) { remoteUncertain = true; shutdownConfirmed = false }
                            error("Owned original music output lost")
                        }
                        val cb = synchronized(lock) {
                            if (state == State.RUNNING && mine == generation) listener else null
                        }
                        cb?.onBeat(output.bpm ?: 0.0, output.level.toFloat())
                        cb?.onSpectrum(output.bass.toFloat(), output.mid.toFloat(),
                            output.presence.toFloat(), output.treble.toFloat(),
                            output.beat, output.confidence.toFloat())
                    }
                }
                if (!received && original == null) {
                    // Scanner, meteor, wave and timer-pulse keep moving when a
                    // valid capture temporarily yields no PCM. The same owned
                    // engine/rate limiter is used; NO new source or Music socket.
                    val current = synchronized(lock) {
                        if (state == State.RUNNING && mine == generation) engine else null
                    }
                    val tick = current?.clockTick(clock())
                    if (tick?.failed?.isNotEmpty() == true) {
                        synchronized(lock) { remoteUncertain = true; shutdownConfirmed = false }
                        error("Owned timed music output lost")
                    }
                    idle()
                }
            }
        } catch (error: Throwable) {
            val callback = synchronized(lock) {
                if (state == State.RUNNING && mine == generation) listener else null
            }
            try { callback?.onError("Audio/scene runtime stopped safely") }
            finally {
                // A failed STOP remains observable by the foreground UI's
                // subsequent stop call and permanently fences new output.
                try { stop() } catch (_: RuntimeException) { }
            }
        }
    }

    override fun stop() {
        synchronized(stopSerial) {
            val session: ClassicPcmCaptureSession?
            val localWorker: Thread?
            val localEngine: ClassicDjEngine?
            val callback: DjRuntime.Listener?
            val pendingStart: CountDownLatch?
            val pendingStarter: Thread?
            synchronized(lock) {
                if (state == State.STOPPING || state == State.CLOSED) {
                    check(shutdownConfirmed && !remoteUncertain) {
                        "Owned Music shutdown not confirmed"
                    }
                    return
                }
                // A STARTING session owns a temporary engine which is not yet
                // published in 'engine'. That starter, not this stop caller,
                // must roll it back; STOP cannot report success first.
                pendingStart = if (state == State.STARTING) startFinished else null
                pendingStarter = if (state == State.STARTING) startThread else null
                state = State.STOPPING
                ++generation // fence all late read-loop callbacks
                session = capture; capture = null
                localEngine = engine; engine = null
                inputBridge = null
                clockOnly = false
                originalCallbackOwner = false
                originalOutputGuard = null
                pendingOriginal = null
                localWorker = worker; worker = null
                callback = listener; listener = null
            }
            var confirmed = false
            try {
                // Always try remote cleanup even if capture close fails.
                var closeError: Throwable? = null
                try { session?.close() } catch (failure: Throwable) {
                    closeError = failure
                }
                val states = try { localEngine?.stop() } catch (failure: Throwable) {
                    if (closeError == null) closeError = failure
                    else closeError.addSuppressed(failure)
                    null
                }
                if (localEngine?.uncertainOutput() == true ||
                    states?.values?.any { it != ClassicMusicCoordinator.State.IDLE } == true)
                    synchronized(lock) { remoteUncertain = true }
                if (localWorker != null && localWorker !== Thread.currentThread()) {
                    localWorker.join(1200L)
                }
                // No new Activity may acquire reverse output before the
                // in-flight source/LAN handshake has finished its own cleanup.
                // Timeout fails CLOSED and quarantines the UI output gate.
                if (pendingStart != null && pendingStarter !== Thread.currentThread()) {
                    check(pendingStart.await(12L, TimeUnit.SECONDS)) {
                        "Original Music handshake cleanup not confirmed"
                    }
                }
                if (closeError != null ||
                    synchronized(lock) { remoteUncertain || !shutdownConfirmed }) {
                    throw IllegalStateException("Owned Music cleanup not confirmed", closeError)
                }
                confirmed = true
            } finally {
                synchronized(lock) {
                    state = State.CLOSED
                    shutdownConfirmed = confirmed
                }
                callback?.onState(if (confirmed) "STOPPED" else "STOP_UNCONFIRMED")
            }
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
