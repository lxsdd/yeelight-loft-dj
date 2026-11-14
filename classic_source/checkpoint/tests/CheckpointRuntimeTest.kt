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
    val error = CountDownLatch(1)
    val states = mutableListOf<String>()
    @Volatile var lastBpm = -1.0
    @Volatile var lastLevel = -1.0f
    override fun onState(state: String) { synchronized(states) { states.add(state) } }
    override fun onBeat(bpm: Double, level: Float) { lastBpm = bpm; lastLevel = level; beat.countDown() }
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
    check(!runtime.configure(Double.NaN, "BEAT"))
    check(!runtime.configure(0.1, "BEAT"))
    check(!runtime.configure(2.0, "BEAT\nset_power"))
    check(runCatching { runtime.start(Context(), "internal", emptyList(), result) }.isFailure)
    runtime.start(Context(), "microphone", emptyList(), result)
    check(result.beat.await(4, TimeUnit.SECONDS)) { "No PCM output" }
    check(result.lastLevel.isFinite() && result.lastLevel in 0f..1f)
    check(runtime.configure(2.0, "BEAT"))
    check(runtime.configure(1.0, "SPECTRUM"))
    check(runtime.configure(0.25, "AMBIENT"))
    check(runCatching { runtime.start(Context(), "microphone", emptyList(), result) }.isFailure)
    runtime.stop(); runtime.stop()
    check(sample.closed)
    check(!runtime.configure(1.0, "SPECTRUM"))
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
    println("PASS Classic runtime PCM/FFT lifecycle, explicit 1-lamp transport lifecycle, denied IPs, double-start, and failure path")
}
