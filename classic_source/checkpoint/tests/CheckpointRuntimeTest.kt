package io.github.lxsdd.yeelightclassicdj.runtime

import android.content.Context
import loftdj.classic.ClassicPcmCaptureSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class MockMic(private val throwRead: Boolean = false) : ClassicPcmCaptureSession.Input {
    @Volatile var closed = false
    @Volatile var reads = 0
    override fun read(buffer: ShortArray): Int {
        reads++
        if (throwRead) error("injected capture failure")
        if (closed) return -1
        for (i in buffer.indices) buffer[i] = (12000 * kotlin.math.sin(2.0 * Math.PI * i / 150.0)).toInt().toShort()
        return buffer.size
    }
    override fun close() { closed = true }
}

private class Listener : DjRuntime.Listener {
    val beat = CountDownLatch(1)
    val spectrum = CountDownLatch(1)
    val error = CountDownLatch(1)
    @Volatile var lastBands = floatArrayOf()
    @Volatile var lastConfidence = -1.0f
    @Volatile var lastBeatFlag = false
    val states = mutableListOf<String>()
    @Volatile var lastBpm = -1.0
    @Volatile var lastLevel = -1.0f
    override fun onState(state: String) { synchronized(states) { states.add(state) } }
    override fun onBeat(bpm: Double, level: Float) { lastBpm = bpm; lastLevel = level; beat.countDown() }
    override fun onSpectrum(bass: Float, mid: Float, presence: Float, treble: Float,
                            beat: Boolean, confidence: Float) {
        lastBands = floatArrayOf(bass, mid, presence, treble)
        lastBeatFlag = beat
        lastConfidence = confidence
        spectrum.countDown()
    }
    override fun onError(message: String) { error.countDown() }
}

fun main() {
    val sample = MockMic()
    val runtime = CheckpointRuntime()
    runtime.inputFactory = { sample }
    var now = 0L
    runtime.clock = { now += 64; now }
    runtime.idle = { Thread.sleep(1) }
    val result = Listener()
    check(runtime.configure(0.5, "AMBIENT")) // next start has these settings
    check(runtime.configurePulse(0x18BEF0, true, 120, 12, 90, 90, 600, 2.0))
    check(!runtime.configurePulse(-1, true, 120, 12, 90, 90, 600, 2.0))
    check(!runtime.configurePulse(0x18BEF0, true, 120, 0, 90, 90, 600, 2.0))
    check(!runtime.configurePulse(0x18BEF0, true, 120, 12, 90, 90, 600, Double.NaN))
    check(!runtime.configure(Double.NaN, "BEAT"))
    check(!runtime.configure(0.1, "BEAT"))
    check(!runtime.configure(2.0, "BEAT\nset_power"))
    check(runCatching { runtime.start(Context(), "internal", emptyList(), result) }.isFailure)
    runtime.start(Context(), "microphone", emptyList(), result)
    check(result.beat.await(4, TimeUnit.SECONDS)) { "No PCM output" }
    check(result.spectrum.await(4, TimeUnit.SECONDS)) { "No actual FFT output" }
    check(result.lastBands.size == 4 && result.lastBands.all { it.isFinite() && it in 0f..1f })
    check(result.lastConfidence in 0f..1f)
    check(result.lastBands.any { it > 0f }) { "Measured sine-wave FFT was all zeros" }
    check(result.lastLevel.isFinite() && result.lastLevel in 0f..1f)
    check(runtime.configure(2.0, "BEAT"))
    check(runtime.configure(2.0, "PULSE"))
    check(runtime.configurePulse(0xFF0033, false, 90, 8, 100, 70, 330, 1.2))
    check(runtime.configure(1.0, "SPECTRUM"))
    check(runtime.configure(0.25, "AMBIENT"))
    check(runCatching { runtime.start(Context(), "microphone", emptyList(), result) }.isFailure)
    runtime.stop(); runtime.stop()
    check(sample.closed)
    check(!runtime.configure(1.0, "SPECTRUM"))
    check(!runtime.configurePulse(0xFFFFFF, false, 90, 8, 100, 70, 330, 1.2))
    check(result.states.contains("MIC_ANALYSIS_ONLY_NO_LAMPS"))
    check(result.states.contains("STOPPED"))
    check(runCatching { runtime.start(Context(), "microphone", emptyList(), result) }.isFailure)

    val failure = CheckpointRuntime()
    val broken = MockMic(throwRead = true)
    failure.inputFactory = { broken }
    val cb = Listener()
    failure.start(Context(), "microphone", emptyList(), cb)
    check(cb.error.await(4, TimeUnit.SECONDS)) { "Capture exception not delivered" }
    failure.stop()
    check(broken.closed)
    // Source-open failures reach the real DjRuntime boundary as safe categories.
    // No initial microphone, playback or lamp callback may claim a live session.
    val sourceFailures: List<Pair<() -> ClassicPcmCaptureSession.Input, DjRuntime.CaptureFailureKind>> = listOf(
        Pair({ throw SecurityException("private diagnostic only") }, DjRuntime.CaptureFailureKind.PERMISSION),
        Pair({ throw IllegalArgumentException("private format") }, DjRuntime.CaptureFailureKind.FORMAT),
        Pair({ throw IllegalStateException("private state") }, DjRuntime.CaptureFailureKind.STATE),
        Pair({ throw NoSuchMethodError("private ABI") }, DjRuntime.CaptureFailureKind.ABI),
        Pair({ throw RuntimeException("private unknown") }, DjRuntime.CaptureFailureKind.UNKNOWN)
    )
    for ((source, expected) in sourceFailures) {
        val rejected = CheckpointRuntime()
        rejected.inputFactory = source
        val thrown = runCatching {
            rejected.start(Context(), "microphone", emptyList(), Listener())
        }.exceptionOrNull()
        check(thrown is DjRuntime.CaptureStartFailure && thrown.kind == expected) {
            "Microphone source failure should be classified $expected, got $thrown"
        }
        check(thrown.cause != null)
        rejected.stop()
    }
    // Real production source-ordering contract: a microphone-open denial must
    // not even read Wi-Fi or begin any Music socket handshake. The same check
    // covers future verified participant adapters without authorizing them.
    val rejectedParticipant = CheckpointRuntime()
    var touchedNetwork = false
    rejectedParticipant.inputFactory = { throw SecurityException("synthetic audio deny") }
    rejectedParticipant.wifiAddress = { touchedNetwork = true; "192.168.0.12" }
    rejectedParticipant.transportFactory = { _, _ ->
        touchedNetwork = true
        error("No socket allowed before microphone open")
    }
    val denied = runCatching {
        rejectedParticipant.start(Context(), "microphone", listOf("192.168.55.42"), Listener())
    }.exceptionOrNull()
    check(denied is DjRuntime.CaptureStartFailure &&
          denied.kind == DjRuntime.CaptureFailureKind.PERMISSION)
    check(!touchedNetwork) { "Source denied but network/participant was touched" }
    rejectedParticipant.stop()

    // The OEM-Visualizer route must reuse the production PCM/FFT/beat bridge,
    // without a MediaProjection token or any additional lamp participant.
    val vizSource = MockMic()
    val vizRuntime = CheckpointRuntime()
    vizRuntime.visualizerFactory = { id ->
        check(id == 17) { "The exact OEM-owned session ID must be forwarded" }
        vizSource
    }
    val vizListener = Listener()
    check(runCatching {
        vizRuntime.startVisualizer(Context(), -1, emptyList(), Listener())
    }.isFailure)
    vizRuntime.startVisualizer(Context(), 17, emptyList(), vizListener)
    check(vizListener.spectrum.await(4, TimeUnit.SECONDS)) {
        "Original-Visualizer route did not reach shared measured DSP"
    }
    check(vizListener.lastBands.size == 4 && vizListener.lastBands.any { it > 0f })
    check(vizListener.states.contains("MIC_ANALYSIS_ONLY_NO_LAMPS"))
    vizRuntime.stop()
    check(vizSource.closed)

    val visualizerDenied = CheckpointRuntime()
    var visualizerNetworkTouched = false
    visualizerDenied.visualizerFactory = { throw SecurityException("synthetic Visualizer deny") }
    visualizerDenied.wifiAddress = {
        visualizerNetworkTouched = true; "192.168.1.2"
    }
    visualizerDenied.transportFactory = { _, _ ->
        visualizerNetworkTouched = true
        error("No Music socket permitted")
    }
    val visualizerError = runCatching {
        visualizerDenied.startVisualizer(Context(), 0, listOf("192.168.55.42"), Listener())
    }.exceptionOrNull()
    check(visualizerError is DjRuntime.CaptureStartFailure &&
          visualizerError.kind == DjRuntime.CaptureFailureKind.PERMISSION)
    check(!visualizerNetworkTouched)
    visualizerDenied.stop()
    // Integration acceptance: user-picked one Color4 is registered, owned and stopped.
    // No sockets or actual lamp writes: injected in-memory reverse transport.
    val selected = CheckpointRuntime()
    val mic = MockMic()
    selected.inputFactory = { mic }
    selected.clock = { System.nanoTime() / 1_000_000L }
    selected.wifiAddress = { "192.168.0.12" }
    val transport = object : loftdj.classic.ClassicMusicCoordinator.Transport {
        var active = false
        var starts = 0
        var stops = 0
        override fun readMusic(id: String): Boolean? = active
        override fun requestStart(id: String): Boolean { starts++; active=true; return true }
        override fun reverseConnected(id: String) = true
        override fun requestStop(id: String): Boolean { stops++; active=false; return true }
        override fun closeReverse(id: String) {}
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int)=true
    }
    selected.transportFactory = { local, ids ->
        check(local == "192.168.0.12" && ids == setOf("192.168.55.42"))
        transport
    }
    val userStatus = Listener()
    selected.start(Context(), "microphone", listOf("192.168.55.42"), userStatus)
    check(userStatus.beat.await(4,TimeUnit.SECONDS))
    check(userStatus.states.contains("DJ_ACTIVE_1_LAMPS"))
    check(selected.configure(4.0, "AMBIENT"))
    check(selected.configure(0.5, "BEAT"))
    check(transport.starts == 1 && transport.stops == 0)
    selected.stop()
    check(mic.closed && transport.starts == 1 && transport.stops == 1 && !transport.active)
    // Invalid/non-private participants never reach transport or mic acquisition.
    val unsafe = CheckpointRuntime()
    unsafe.inputFactory = { error("Input MUST NOT be opened for public IP") }
    check(runCatching { unsafe.start(Context(), "microphone", listOf("8.8.8.8"), Listener()) }.isFailure)
    check(runCatching { unsafe.start(Context(), "microphone", listOf("192.168.1.2", "192.168.1.2"), Listener()) }.isFailure)
    // Real runtime worker: no PCM bytes for the whole period. A confirmed
    // timed spatial scene must still render across the existing reverse session
    // without inventing audio telemetry, additional Music starts or stray stop.
    val timed = CheckpointRuntime()
    val quiet = object : ClassicPcmCaptureSession.Input {
        var closed = false
        override fun read(buffer: ShortArray): Int = if (closed) -1 else 0
        override fun close() { closed = true }
    }
    timed.inputFactory = { quiet }
    timed.clock = { System.nanoTime() / 1_000_000L }
    timed.idle = { Thread.sleep(5) }
    timed.wifiAddress = { "192.168.0.12" }
    val timerTransport = object : loftdj.classic.ClassicMusicCoordinator.Transport {
        var active = false
        var starts = 0
        var stops = 0
        var frames = 0
        override fun readMusic(id: String): Boolean? = active
        override fun requestStart(id: String): Boolean { starts++; active = true; return true }
        override fun reverseConnected(id: String): Boolean = true
        override fun requestStop(id: String): Boolean { stops++; active = false; return true }
        override fun closeReverse(id: String) {}
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int,
                                      brightness: Int): Boolean {
            frames++
            return true
        }
    }
    timed.transportFactory = { _, _ -> timerTransport }
    check(timed.configure(1.0, "SCANNER"))
    val timedListener = Listener()
    timed.start(Context(), "microphone", listOf("192.168.55.42"), timedListener)
    Thread.sleep(850L)
    check(timerTransport.frames >= 2) { "Clock-driven scanner lost without PCM frames" }
    check(!timedListener.beat.await(10, TimeUnit.MILLISECONDS))
    check(timerTransport.starts == 1)
    timed.stop()
    val stoppedFrames = timerTransport.frames
    Thread.sleep(320L)
    check(timerTransport.frames == stoppedFrames) { "Clock tick escaped owned stop" }
    check(timerTransport.stops == 1 && quiet.closed && !timerTransport.active)
    // A real independent scene uses the production session and owned reverse
    // transport without ever requesting an audio source or a forged beat.
    val independent = CheckpointRuntime()
    independent.inputFactory = { error("Timed scene attempted microphone capture") }
    independent.visualizerFactory = { error("Timed scene attempted Visualizer capture") }
    independent.clock = { System.nanoTime() / 1_000_000L }
    independent.idle = { Thread.sleep(5L) }
    independent.wifiAddress = { "192.168.0.12" }
    val autonomousWire = object : loftdj.classic.ClassicMusicCoordinator.Transport {
        var active = false
        var starts = 0
        var stops = 0
        @Volatile var frames = 0
        @Volatile var lastRgb = -1
        @Volatile var lastBrightness = -1
        override fun readMusic(id: String): Boolean? = active
        override fun requestStart(id: String): Boolean { active = true; starts++; return true }
        override fun reverseConnected(id: String) = true
        override fun requestStop(id: String): Boolean { active = false; stops++; return true }
        override fun closeReverse(id: String) {}
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int): Boolean {
            check(generation > 0 && rgb in 0..0xFFFFFF && brightness in 1..100)
            lastRgb = rgb
            lastBrightness = brightness
            frames++
            return true
        }
    }
    independent.transportFactory = { ip, ids ->
        check(ip == "192.168.0.12" && ids == setOf("192.168.55.42"))
        autonomousWire
    }
    val independentStatus = Listener()
    check(!independent.configureSpatial(-2,100,false,4,1,100))
    check(!independent.configureSpatial(0x123456,100,false,4,1,1))
    check(independent.configureSpatial(0x22BB33,200,true,3,5,67))
    check(independent.configure(1.0, "BEAT"))
    check(runCatching {
        independent.startTimedScene(Context(), listOf("192.168.55.42"), independentStatus)
    }.isFailure) // Cannot invent beats without audio.
    check(autonomousWire.starts == 0)
    check(independent.configure(1.0, "SCANNER"))
    check(runCatching {
        independent.startTimedScene(Context(), emptyList(), independentStatus)
    }.isFailure) // No unverified or zero participants.
    check(runCatching {
        independent.startTimedScene(Context(), listOf("8.8.8.8"), independentStatus)
    }.isFailure)
    check(autonomousWire.starts == 0)
    independent.startTimedScene(Context(), listOf("192.168.55.42"), independentStatus)
    check(independentStatus.states.contains("SCENE_TIMED_1_LAMPS"))
    Thread.sleep(850L)
    check(autonomousWire.frames >= 2) { "Independent scanner did not animate" }
    check(autonomousWire.lastRgb == 0x22BB33 && autonomousWire.lastBrightness in 5..67) {
        "Production runtime lost pre-start spatial scene settings"
    }
    val previousStarts = autonomousWire.starts
    check(independent.configureSpatial(0x445566,120,false,2,3,77))
    Thread.sleep(360L)
    check(autonomousWire.lastRgb == 0x445566 && autonomousWire.lastBrightness in 3..77) {
        "Live scene tuning not applied to already-owned output"
    }
    check(autonomousWire.starts == previousStarts)
    check(!independentStatus.beat.await(10L, TimeUnit.MILLISECONDS))
    check(!independentStatus.spectrum.await(10L, TimeUnit.MILLISECONDS))
    check(!independent.configure(1.0, "BEAT"))
    check(!independent.configure(1.0, "SPECTRUM"))
    check(!independent.configure(1.0, "PULSE")) // Default pulse requires beats.
    // Regression: a rejected combined scene edit may not half-apply pulse or
    // geometry. The same owned socket must keep the old scanner and colour.
    val rejectedBundle = DjRuntime.SceneSettings(
        1.0, "PULSE", 0xF0A012, true, 120, 12, 90, 90, 600, 2.0,
        0x111122, 200, true, 4, 8, 88)
    check(!independent.applySceneSettings(rejectedBundle))
    Thread.sleep(360L)
    check(autonomousWire.lastRgb == 0x445566)
    check(!independent.configure(1.0, "PULSE")) // Beat-only still forbidden.
    check(autonomousWire.starts == previousStarts)
    // Even a bad pulse envelope or spatial bound must reject the WHOLE update.
    check(!independent.applySceneSettings(DjRuntime.SceneSettings(
        1.0, "METEOR", 0xFF00FF, false, 120, 12, 90, 90, 600, 2.0,
        0x123456, 200, false, 4, 80, 8)))
    check(!independent.applySceneSettings(DjRuntime.SceneSettings(
        1.0, "METEOR", 0xFF00FF, false, 120, 12, 90, 90, 600, Double.NaN,
        0x123456, 200, false, 4, 8, 80)))
    Thread.sleep(360L)
    check(autonomousWire.lastRgb == 0x445566)
    // Positive: colour+direction+effect+gain+pulse travel as ONE snapshot,
    // without new participant handshake or changing the audio-source owner.
    check(independent.applySceneSettings(DjRuntime.SceneSettings(
        1.5, "METEOR", 0xF0A012, false, 120, 12, 90, 90, 600, 2.0,
        0x226688, 175, true, 3, 6, 72)))
    Thread.sleep(360L)
    check(autonomousWire.lastRgb == 0x226688 &&
          autonomousWire.lastBrightness in 6..72)
    check(autonomousWire.starts == previousStarts)
    check(independent.configure(1.0, "PULSE")) // Bundle explicitly enabled timer pulse.
    check(independent.configurePulse(0xF0A012, false, 120, 12, 90, 90, 600, 2.0))
    check(independent.configure(1.0, "PULSE"))
    check(!independent.configurePulse(0xF0A012, true, 120, 12, 90, 90, 600, 2.0))
    check(independent.configure(1.0, "METEOR"))
    check(independent.configure(1.0, "WAVE"))
    Thread.sleep(360L)
    independent.stop()
    val finalFrames = autonomousWire.frames
    Thread.sleep(340L)
    check(autonomousWire.frames == finalFrames) { "Timed scene continued after stop" }
    check(autonomousWire.starts == 1 && autonomousWire.stops == 1 && !autonomousWire.active)
    check(independentStatus.states.contains("STOPPED"))
    check(!independent.configure(1.0, "SCANNER"))
    check(!independent.configureSpatial(0xABCDEF,200,true,2,2,80))
    check(runCatching {
        independent.startTimedScene(Context(), listOf("192.168.55.42"), Listener())
    }.isFailure)
    // Real Android-independent runtime path, not only a pure geometry helper:
    // an expanded explicitly selected fleet keeps one owned reverse session
    // per lamp, without MIC/Visualizer capture for a timed scene or any
    // fixed four/14-lamp restriction. No actual network I/O is performed.
    for (fleetSize in listOf(5, 15, 33, 72)) {
        val ids = (1..fleetSize).map { "192.168.61.$it" }
        val fleetRuntime = CheckpointRuntime()
        val fleetListener = Listener()
        fleetRuntime.inputFactory = { error("Timed fleet must not open microphone") }
        fleetRuntime.visualizerFactory = { error("Timed fleet must not open Visualizer") }
        fleetRuntime.wifiAddress = { "192.168.0.12" }
        fleetRuntime.clock = { System.nanoTime() / 1_000_000L }
        fleetRuntime.idle = { Thread.sleep(4L) }
        check(fleetRuntime.configure(1.0, "WAVE"))
        val activeIds = ids.associateWith { false }.toMutableMap()
        val startedIds = linkedSetOf<String>()
        val stoppedIds = linkedSetOf<String>()
        val renderedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fleetRuntime.transportFactory = { localIp, participants ->
            check(localIp == "192.168.0.12" && participants == ids.toSet())
            object : loftdj.classic.ClassicMusicCoordinator.Transport {
                override fun readMusic(id: String): Boolean? = activeIds[id]
                override fun requestStart(id: String): Boolean {
                    check(id in ids && startedIds.add(id))
                    activeIds[id] = true
                    return true
                }
                override fun reverseConnected(id: String): Boolean = id in startedIds
                override fun requestStop(id: String): Boolean {
                    check(id in startedIds && stoppedIds.add(id))
                    activeIds[id] = false
                    return true
                }
                override fun closeReverse(id: String) {}
                override fun sendReverseFrame(id: String, generation: Long,
                    rgb: Int, brightness: Int): Boolean {
                    check(id in startedIds && generation > 0 &&
                        rgb in 0..0xFFFFFF && brightness in 1..100)
                    renderedIds.add(id)
                    return true
                }
            }
        }
        fleetRuntime.startTimedScene(Context(), ids, fleetListener)
        check(fleetListener.states.contains("SCENE_TIMED_${fleetSize}_LAMPS"))
        check(startedIds == ids.toSet())
        var attempts = 0
        while (renderedIds.size < fleetSize && attempts++ < 70) Thread.sleep(20L)
        check(renderedIds == ids.toSet()) {
            "Actual timed runtime did not render all $fleetSize selected peers"
        }
        fleetRuntime.stop()
        check(stoppedIds == ids.toSet() && activeIds.values.none { it })
        val priorFrames = renderedIds.size
        Thread.sleep(40L)
        check(renderedIds.size == priorFrames)
    }
    // The genuine original Classic callback now has a production output path
    // through the SAME owned reverse transport, but NO SECOND AudioRecord.
    val original = CheckpointRuntime()
    val originalConsent = java.util.concurrent.atomic.AtomicBoolean(true)
    val originalGuard = object : DjRuntime.ConsentGuard {
        override fun isStillAuthorized() = originalConsent.get()
    }
    original.inputFactory = { error("Original player already owns AudioRecord") }
    original.visualizerFactory = { error("Original player already owns Visualizer") }
    original.clock = { System.nanoTime() / 1_000_000L }
    original.idle = { Thread.sleep(4L) }
    original.wifiAddress = { "192.168.1.10" }
    val originalPeers = mutableMapOf("192.168.1.21" to false, "192.168.1.22" to false)
    val originalFrames = mutableListOf<String>()
    var originalStarts = 0
    var originalStops = 0
    original.transportFactory = { local, ids ->
        check(local == "192.168.1.10" && ids == originalPeers.keys)
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = originalPeers[id]
            override fun requestStart(id: String): Boolean {
                originalStarts++
                originalPeers[id] = true
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                originalStops++
                originalPeers[id] = false
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String, generation: Long,
                                          rgb: Int, brightness: Int): Boolean {
                synchronized(originalFrames) { originalFrames.add(id) }
                return true
            }
        }
    }
    check(!original.submitOriginalFrame(null))
    check(!original.submitOriginalFrame(
        io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalMusicAudioSignal.Frame(
            .5f, floatArrayOf(.7f,.3f,.2f,.1f), true,120f,.8f)))
    check(runCatching {
        original.startOriginalAudio(emptyList(), Listener(), originalGuard)
    }.isFailure)
    val originalListener = Listener()
    check(original.configure(1.0, "SCANNER"))
    original.startOriginalAudio(originalPeers.keys.toList(), originalListener, originalGuard)
    check(originalStarts == 2)
    check(originalListener.states.contains("ORIGINAL_AUDIO_2_LAMPS"))
    val originalFrame = io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalMusicAudioSignal.Frame(
        .6f, floatArrayOf(.8f,.4f,.2f,.1f), true,120f,.75f)
    check(original.submitOriginalFrame(originalFrame))
    check(originalListener.spectrum.await(4, TimeUnit.SECONDS))
    check(originalListener.lastBands[0] == .8f && originalListener.lastConfidence == .75f)
    val deadline = System.nanoTime() + 2_000_000_000L
    while (System.nanoTime() < deadline &&
           synchronized(originalFrames) { originalFrames.toSet().size } < 2)
        Thread.sleep(10L)
    check(synchronized(originalFrames) { originalFrames.toSet() } == originalPeers.keys)
    check(!original.submitOriginalFrame(
        io.github.lxsdd.yeelightclassicdj.ui.ClassicOriginalMusicAudioSignal.Frame(
            .5f, floatArrayOf(Float.NaN,0f,0f,0f),false,0f,0f)))
    // A changed original selection must stop both OWNED channels on the
    // existing real runtime worker, without waiting for an Activity restart.
    originalConsent.set(false)
    check(originalListener.error.await(4, TimeUnit.SECONDS)) {
        "Revoked original owner did not stop live output"
    }
    var stopPolls = 0
    while (originalStops < 2 && stopPolls++ < 100) Thread.sleep(20L)
    original.stop()
    check(originalStops == 2 && originalPeers.values.none { it })
    val frozen = synchronized(originalFrames) { originalFrames.size }
    check(!original.submitOriginalFrame(originalFrame))
    Thread.sleep(350L)
    check(synchronized(originalFrames) { originalFrames.size } == frozen)
    // Any foreign active Music owner is never taken over.
    val foreign = CheckpointRuntime()
    foreign.wifiAddress = { "192.168.1.10" }
    var foreignStarts = 0
    foreign.transportFactory = { _, _ ->
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = true
            override fun requestStart(id: String): Boolean {
                foreignStarts++
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String) = true
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String,generation: Long,rgb: Int,brightness: Int)=true
        }
    }
    check(runCatching {
        foreign.startOriginalAudio(listOf("192.168.1.21"), Listener(),
            object : DjRuntime.ConsentGuard {
                override fun isStillAuthorized() = true
            })
    }.isFailure)
    check(foreignStarts == 0)
    foreign.stop()
    // Absent/revoked OEM consent must fail before even looking up the Wi-Fi
    // interface, creating a transport or negotiating any bulb.
    val consentDenied = CheckpointRuntime()
    var deniedTouchedNetwork = false
    consentDenied.wifiAddress = { deniedTouchedNetwork = true; "192.168.1.10" }
    consentDenied.transportFactory = { _, _ ->
        deniedTouchedNetwork = true
        error("No network before original consent")
    }
    check(runCatching {
        consentDenied.startOriginalAudio(listOf("192.168.1.21"), Listener(),
            object : DjRuntime.ConsentGuard {
                override fun isStillAuthorized() = false
            })
    }.isFailure)
    check(!deniedTouchedNetwork)
    consentDenied.stop()

    // The positive consent is for the complete chosen cohort, not a hidden
    // subset. If B is already foreign-owned, A must be stopped again.
    val partial = CheckpointRuntime()
    val partialActive = mutableMapOf("192.168.1.21" to false, "192.168.1.22" to true)
    var partialStarts = 0
    var partialStops = 0
    partial.wifiAddress = { "192.168.1.10" }
    partial.transportFactory = { _, _ ->
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = partialActive[id]
            override fun requestStart(id: String): Boolean {
                partialStarts++
                partialActive[id] = true
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                partialStops++
                partialActive[id] = false
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String, generation: Long,
                                          rgb: Int, brightness: Int) = true
        }
    }
    check(runCatching {
        partial.startOriginalAudio(partialActive.keys.toList(), Listener(),
            object : DjRuntime.ConsentGuard {
                override fun isStillAuthorized() = true
            })
    }.isFailure)
    check(partialStarts == 1 && partialStops == 1 &&
          partialActive["192.168.1.21"] == false &&
          partialActive["192.168.1.22"] == true) {
        "Partial original cohort failure changed a foreign session or leaked owned A"
    }
    partial.stop()

    // Real production runtime, in-memory ONLY: a failed reverse frame must
    // revoke the whole chosen show for original, timer and microphone paths.
    // No LAN, APK or device is touched by this regression.
    fun failingFleetRuntime(): Pair<CheckpointRuntime, Triple<
        java.util.concurrent.ConcurrentHashMap<String, Boolean>,
        java.util.concurrent.CopyOnWriteArrayList<String>,
        java.util.concurrent.CopyOnWriteArrayList<String>>> {
        val ids = listOf("192.168.71.21", "192.168.71.22", "192.168.71.23")
        val remote = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        ids.forEach { remote[it] = false }
        val stops = java.util.concurrent.CopyOnWriteArrayList<String>()
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val runtime = CheckpointRuntime()
        runtime.clock = { System.nanoTime() / 1_000_000L }
        runtime.idle = { Thread.sleep(4L) }
        runtime.wifiAddress = { "192.168.71.10" }
        runtime.transportFactory = { _, selected ->
            check(selected == ids.toSet())
            object : loftdj.classic.ClassicMusicCoordinator.Transport {
                override fun readMusic(id: String): Boolean? = remote[id]
                override fun requestStart(id: String): Boolean {
                    remote[id] = true
                    return true
                }
                override fun reverseConnected(id: String) = true
                override fun requestStop(id: String): Boolean {
                    check(!stops.contains(id)) { "Double stop of confirmed peer" }
                    stops.add(id)
                    remote[id] = false
                    return true
                }
                override fun closeReverse(id: String) {}
                override fun sendReverseFrame(id: String, generation: Long,
                                              rgb: Int, brightness: Int): Boolean {
                    check(generation > 0 && rgb in 0..0xFFFFFF && brightness in 1..100)
                    writes.add(id)
                    return id != ids[1]
                }
            }
        }
        return runtime to Triple(remote, stops, writes)
    }
    fun awaitFaultCleanup(listener: Listener, stops: List<String>, writes: List<String>) {
        check(listener.error.await(4, TimeUnit.SECONDS)) {
            "One failed output must report whole-session error to Classic host"
        }
        var polls = 0
        while (stops.size < 2 && polls++ < 120) Thread.sleep(10L)
        check(stops.toSet() == setOf("192.168.71.21", "192.168.71.23")) {
            "Only surviving OWNED peers may receive stop: $stops"
        }
        val frozen = writes.size
        Thread.sleep(360L)
        check(writes.size == frozen) { "Frames escaped after whole-cohort failure" }
    }
    val (failedOriginal, originalFault) = failingFleetRuntime()
    val originalFaultListener = Listener()
    failedOriginal.startOriginalAudio(
        listOf("192.168.71.21", "192.168.71.22", "192.168.71.23"),
        originalFaultListener, object : DjRuntime.ConsentGuard {
            override fun isStillAuthorized() = true
        })
    check(failedOriginal.submitOriginalFrame(originalFrame))
    awaitFaultCleanup(originalFaultListener, originalFault.second, originalFault.third)
    check(!failedOriginal.submitOriginalFrame(originalFrame))
    check(originalFault.first["192.168.71.22"] == true) {
        "Uncertain remote state must not receive speculative STOP"
    }
    check(runCatching { failedOriginal.stop() }.isFailure) {
        "Uncertain ORIGINAL remote frame must quarantine output admission"
    }

    val (failedTimed, timedFault) = failingFleetRuntime()
    val timedFaultListener = Listener()
    check(failedTimed.configure(1.0, "SCANNER"))
    failedTimed.startTimedScene(Context(),
        listOf("192.168.71.21", "192.168.71.22", "192.168.71.23"),
        timedFaultListener)
    awaitFaultCleanup(timedFaultListener, timedFault.second, timedFault.third)
    check(runCatching { failedTimed.stop() }.isFailure) {
        "Uncertain timed reverse frame must quarantine output admission"
    }

    val (failedMic, micFault) = failingFleetRuntime()
    val micFaultInput = MockMic()
    failedMic.inputFactory = { micFaultInput }
    val micFaultListener = Listener()
    failedMic.start(Context(), "microphone",
        listOf("192.168.71.21", "192.168.71.22", "192.168.71.23"),
        micFaultListener)
    awaitFaultCleanup(micFaultListener, micFault.second, micFault.third)
    check(micFaultInput.closed) { "Mic remained open after reverse-output failure" }
    check(runCatching { failedMic.stop() }.isFailure) {
        "Uncertain microphone reverse frame must quarantine output admission"
    }

    check(originalFault.third.count { it == "192.168.71.22" } == 1)
    check(timedFault.third.count { it == "192.168.71.22" } == 1)
    check(micFault.third.count { it == "192.168.71.22" } == 1)


    // Production startup/stop race: the private Music reverse handshake can
    // be blocked inside a LAN call while the original Activity disappears.
    // stop() must NOT return until that staged owner has rolled back its
    // confirmed reverse socket; otherwise the next Activity can race it.
    val delayed = CheckpointRuntime()
    val delayedEntered = CountDownLatch(1)
    val delayedRelease = CountDownLatch(1)
    val delayedStartDone = CountDownLatch(1)
    val delayedStopDone = CountDownLatch(1)
    val delayedActive = java.util.concurrent.atomic.AtomicBoolean(false)
    val delayedStops = java.util.concurrent.atomic.AtomicInteger()
    val delayedStartFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val delayedStopFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    delayed.wifiAddress = { "192.168.22.10" }
    delayed.transportFactory = { _, ids ->
        check(ids == setOf("192.168.22.21"))
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = delayedActive.get()
            override fun requestStart(id: String): Boolean {
                delayedEntered.countDown()
                check(delayedRelease.await(5L, TimeUnit.SECONDS))
                delayedActive.set(true)
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                delayedStops.incrementAndGet()
                delayedActive.set(false)
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String,generation: Long,
                rgb: Int,brightness: Int) = true
        }
    }
    val delayedGuard = object : DjRuntime.ConsentGuard {
        override fun isStillAuthorized() = true
    }
    val firstStarter = Thread {
        try {
            delayed.startOriginalAudio(listOf("192.168.22.21"), Listener(), delayedGuard)
        } catch (t: Throwable) {
            delayedStartFailure.set(t)
        } finally { delayedStartDone.countDown() }
    }
    firstStarter.start()
    check(delayedEntered.await(4L,TimeUnit.SECONDS)) {
        "The injected ORIGINAL reverse handshake did not begin"
    }
    Thread {
        try { delayed.stop() }
        catch (t: Throwable) { delayedStopFailure.set(t) }
        finally { delayedStopDone.countDown() }
    }.start()
    check(!delayedStopDone.await(80L,TimeUnit.MILLISECONDS)) {
        "STOP returned while staged reverse Music handshake was still blocked"
    }
    delayedRelease.countDown()
    check(delayedStartDone.await(5L,TimeUnit.SECONDS))
    check(delayedStopDone.await(5L,TimeUnit.SECONDS)) {
        "STOP did not wait for private staged Music cleanup"
    }
    check(delayedStartFailure.get()!=null && delayedStopFailure.get()==null)
    check(!delayedActive.get() && delayedStops.get()==1) {
        "Confirmed staged Music socket not closed before STOP returned"
    }
    check(!delayed.submitOriginalFrame(originalFrame)) {
        "Late PCM callback escaped staged stop"
    }
    check(runCatching {
        delayed.startOriginalAudio(listOf("192.168.22.21"), Listener(), delayedGuard)
    }.isFailure) { "Closed runtime must not restart the old owner" }

    // Duplicate STOP callers are serialized until the real owned transport
    // confirms closure; a second caller must not prematurely release the
    // process-global output gate.
    val doubleStop = CheckpointRuntime()
    val enteredStop = CountDownLatch(1)
    val releaseStop = CountDownLatch(1)
    val firstStopped = CountDownLatch(1)
    val secondStopped = CountDownLatch(1)
    val stopFault = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val remoteStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    val stopCount = java.util.concurrent.atomic.AtomicInteger()
    doubleStop.wifiAddress = { "192.168.22.10" }
    doubleStop.transportFactory = { _, _ ->
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = !remoteStopped.get()
            override fun requestStart(id: String) = true
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                enteredStop.countDown()
                check(releaseStop.await(5L,TimeUnit.SECONDS))
                stopCount.incrementAndGet()
                remoteStopped.set(true)
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String,gen: Long,rgb: Int,bri: Int)=true
        }
    }
    // Existing foreign music may not be taken over: use a separate initial
    // false state before the explicit confirmed own start.
    val ownInitially = java.util.concurrent.atomic.AtomicBoolean(false)
    // Rebind real transport above to honor the initial negative state.
    doubleStop.transportFactory = { _, _ ->
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? =
                if (remoteStopped.get()) false else ownInitially.get()
            override fun requestStart(id: String): Boolean {
                ownInitially.set(true); return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                enteredStop.countDown()
                check(releaseStop.await(5L,TimeUnit.SECONDS))
                stopCount.incrementAndGet()
                remoteStopped.set(true)
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String,gen: Long,rgb: Int,bri: Int)=true
        }
    }
    doubleStop.startOriginalAudio(listOf("192.168.22.22"), Listener(), delayedGuard)
    Thread {
        try { doubleStop.stop() }
        catch (t: Throwable) { stopFault.set(t) }
        finally { firstStopped.countDown() }
    }.start()
    check(enteredStop.await(4L,TimeUnit.SECONDS))
    Thread {
        try { doubleStop.stop() }
        catch (t: Throwable) { stopFault.set(t) }
        finally { secondStopped.countDown() }
    }.start()
    check(!secondStopped.await(80L,TimeUnit.MILLISECONDS)) {
        "Second STOP returned before first reverse close finished"
    }
    releaseStop.countDown()
    check(firstStopped.await(5L,TimeUnit.SECONDS) &&
          secondStopped.await(5L,TimeUnit.SECONDS))
    check(stopFault.get()==null && stopCount.get()==1 && remoteStopped.get()) {
        "Concurrent stop callers did not share ONE confirmed close"
    }


    // Even when reverse requestStop returns without throwing, a negative ACK
    // must not be mistaken for safe shutdown and a new Music owner admitted.
    val unconfirmedStop = CheckpointRuntime()
    val remoteStillActive = java.util.concurrent.atomic.AtomicBoolean(false)
    val stopAttemptCount = java.util.concurrent.atomic.AtomicInteger()
    unconfirmedStop.wifiAddress = { "192.168.72.10" }
    unconfirmedStop.transportFactory = { _, _ ->
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = remoteStillActive.get()
            override fun requestStart(id: String): Boolean {
                remoteStillActive.set(true)
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                stopAttemptCount.incrementAndGet()
                return false // Synthetic firmware rejects ownership STOP.
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String,gen: Long,rgb: Int,bri: Int)=true
        }
    }
    val unconfirmedListener = Listener()
    unconfirmedStop.startOriginalAudio(listOf("192.168.72.21"),
        unconfirmedListener, delayedGuard)
    check(runCatching { unconfirmedStop.stop() }.isFailure) {
        "Negative remote STOP ACK must not release original Music lease"
    }
    check(stopAttemptCount.get()==1 && remoteStillActive.get())
    check(runCatching { unconfirmedStop.stop() }.isFailure) {
        "Subsequent UI cleanup cannot falsely turn an uncertain STOP into success"
    }
    check(unconfirmedListener.states.contains("STOP_UNCONFIRMED")) {
        "Original Music host must receive truthful STOP uncertainty"
    }


    // The real original MusicActivity can opt into a time-based scene with
    // original identity/LAN output consent but NO source or audio callbacks.
    // Test the actual runtime+owned engine, never a second recorder or LAN.
    val ownedTimer = CheckpointRuntime()
    val ownedTimerConsent = java.util.concurrent.atomic.AtomicBoolean(false)
    val ownedTimerGuard = object : DjRuntime.ConsentGuard {
        override fun isStillAuthorized() = ownedTimerConsent.get()
    }
    var timedNetworkTouched = false
    ownedTimer.wifiAddress = { timedNetworkTouched = true; "192.168.38.10" }
    ownedTimer.inputFactory = { error("Clock-only Classic attempted AudioRecord") }
    ownedTimer.visualizerFactory = { error("Clock-only Classic attempted Visualizer") }
    ownedTimer.clock = { System.nanoTime() / 1_000_000L }
    ownedTimer.idle = { Thread.sleep(4L) }
    check(ownedTimer.configure(1.0, "SCANNER"))
    check(runCatching {
        ownedTimer.startOriginalTimedScene(listOf("192.168.38.21"),
            Listener(), ownedTimerGuard)
    }.isFailure)
    check(!timedNetworkTouched) {
        "Denied timed Classic grant touched Wi-Fi or lamp transport"
    }
    val timerRemote = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    val timerPeers = listOf("192.168.38.21", "192.168.38.22")
    timerPeers.forEach { timerRemote[it] = false }
    val timerStarts = java.util.concurrent.atomic.AtomicInteger()
    val timerStops = java.util.concurrent.atomic.AtomicInteger()
    val timerFrames = java.util.concurrent.atomic.AtomicInteger()
    ownedTimer.transportFactory = { _, ids ->
        check(ids == timerPeers.toSet())
        object : loftdj.classic.ClassicMusicCoordinator.Transport {
            override fun readMusic(id: String): Boolean? = timerRemote[id]
            override fun requestStart(id: String): Boolean {
                timerStarts.incrementAndGet()
                timerRemote[id] = true
                return true
            }
            override fun reverseConnected(id: String) = true
            override fun requestStop(id: String): Boolean {
                timerStops.incrementAndGet()
                timerRemote[id] = false
                return true
            }
            override fun closeReverse(id: String) {}
            override fun sendReverseFrame(id: String, generation: Long,
                                          rgb: Int, brightness: Int): Boolean {
                check(id in timerPeers && generation > 0 && brightness in 1..100)
                timerFrames.incrementAndGet()
                return true
            }
        }
    }
    ownedTimerConsent.set(true)
    val timerOwnerListener = Listener()
    ownedTimer.startOriginalTimedScene(timerPeers, timerOwnerListener, ownedTimerGuard)
    check(timerOwnerListener.states.contains("SCENE_TIMED_2_LAMPS"))
    check(timerStarts.get() == 2 && timedNetworkTouched)
    check(!ownedTimer.submitOriginalFrame(originalFrame)) {
        "Source-free timed Classic must reject OEM audio injection"
    }
    var timedPolls = 0
    while (timerFrames.get() < 2 && timedPolls++ < 120) Thread.sleep(20L)
    check(timerFrames.get() >= 2) { "Owned original timed scanner never emitted frames" }
    check(ownedTimer.configure(1.0, "METEOR"))
    check(!ownedTimer.configure(1.0, "BEAT")) {
        "Clock-owned original scene must reject beat-only mode without audio"
    }
    check(timerStarts.get() == 2) { "Scene change reopened a Music session" }
    // Original group/host consent loss must be caught by the runtime worker
    // even if the Activity refresh loop is delayed or the OEM player is silent.
    ownedTimerConsent.set(false)
    check(timerOwnerListener.error.await(4L, TimeUnit.SECONDS)) {
        "Timed original owner revocation did not stop light output"
    }
    timedPolls = 0
    while (timerStops.get() < 2 && timedPolls++ < 120) Thread.sleep(10L)
    check(timerStops.get() == 2 && timerRemote.values.none { it }) {
        "Revoked timed group did not close both independently OWNED channels"
    }
    ownedTimer.stop()
    val timerFramesAfterStop = timerFrames.get()
    Thread.sleep(350L)
    check(timerFrames.get() == timerFramesAfterStop)
    check(runCatching {
        ownedTimer.startOriginalTimedScene(timerPeers, Listener(), ownedTimerGuard)
    }.isFailure)

    // Even WITH valid original consent, no clock-only start is allowed for
    // spectrum, beat or beat-synced pulse, and no network preflight may occur.
    for (unclockable in listOf("BEAT", "SPECTRUM", "PULSE")) {
        val deniedClock = CheckpointRuntime()
        var reachedWifi = false
        deniedClock.wifiAddress = {
            reachedWifi = true
            error("Unclockable scene must not touch LAN")
        }
        check(deniedClock.configure(1.0, unclockable))
        check(runCatching {
            deniedClock.startOriginalTimedScene(listOf("192.168.38.21"),
                Listener(), object : DjRuntime.ConsentGuard {
                    override fun isStillAuthorized() = true
                })
        }.isFailure)
        check(!reachedWifi) { "$unclockable attempted timer-driven output" }
    }

    println("PASS original Classic PCM/FFT -> production owned-only reverse frames; no second recorder, foreign-session takeover, or post-stop output")
    println("PASS dynamic 5/15/33/72 selected LAN peers through real timed runtime")
    println("PASS Classic independent timed scenes, shared owned engine, zero audio/FFT, live switching and stop")
}
