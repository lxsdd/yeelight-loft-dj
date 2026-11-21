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
    println("PASS Classic runtime source-first safety, real clock-driven scanner without PCM, stop fence, FFT and owner failures")
}
