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
    check(runCatching { runtime.start(Context(), "internal", result) }.isFailure)
    runtime.start(Context(), "microphone", result)
    check(result.beat.await(4, TimeUnit.SECONDS)) { "No PCM output" }
    check(result.lastLevel.isFinite() && result.lastLevel in 0f..1f)
    check(runCatching { runtime.start(Context(), "microphone", result) }.isFailure)
    runtime.stop(); runtime.stop()
    check(sample.closed)
    check(result.states.contains("MIC_ANALYSIS_ONLY_NO_LAMPS"))
    check(result.states.contains("STOPPED"))
    check(runCatching { runtime.start(Context(), "microphone", result) }.isFailure)

    val failure = CheckpointRuntime()
    val broken = MockMic(throwRead = true)
    failure.inputFactory = { broken }
    val cb = Listener()
    failure.start(Context(), "microphone", cb)
    check(cb.error.await(4, TimeUnit.SECONDS)) { "Capture exception not delivered" }
    failure.stop()
    check(broken.closed)
    println("PASS Classic runtime PCM/FFT lifecycle, no-device guard, double-start, and failure path")
}
