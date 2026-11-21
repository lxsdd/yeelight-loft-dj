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
    println("PASS Classic independent timed scenes, shared owned engine, zero audio/FFT, live switching and stop")
}
