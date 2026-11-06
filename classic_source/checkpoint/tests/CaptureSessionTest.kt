package loftdj.classic

fun main() {
    class FakeInput : ClassicPcmCaptureSession.Input {
        var reads = 0; var closes = 0; var result = 1024; var lastSize = 0
        override fun read(buffer: ShortArray): Int { reads++; lastSize = buffer.size; return result }
        override fun close() { closes++ }
    }
    val transport = object : ClassicMusicCoordinator.Transport {
        override fun readMusic(id: String) = false
        override fun requestStart(id: String) = true
        override fun reverseConnected(id: String) = true
        override fun requestStop(id: String) = true
        override fun closeReverse(id: String) {}
        override fun sendReverseFrame(id: String, generation: Long, rgb: Int, brightness: Int) = true
    }
    val input = FakeInput()
    var now = 100L
    var outputs = 0
    val capture = ClassicPcmCaptureSession(ClassicPcmDjBridge(ClassicDjEngine(ClassicMusicCoordinator(transport))), input, { now }) { outputs++ }
    capture.start()
    check(capture.poll() && outputs == 1)
    check(!capture.poll() && outputs == 1) // repeated timestamp is dropped
    now++
    input.result = 100
    check(!capture.poll() && outputs == 1) // partial window is dropped
    input.result = 924
    check(capture.poll() && outputs == 2) // 100+924 forms one valid window
    check(input.lastSize == 924)
    input.result = 1024
    now++
    check(capture.poll() && outputs == 3)
    input.result = 100
    check(!capture.poll())
    check(runCatching { capture.poll(2048) }.isFailure) // resizing a partial frame fails closed
    check(capture.state() == ClassicPcmCaptureSession.State.FAILED)
    capture.stop(); capture.close(); capture.close()
    check(input.closes == 1)
    check(runCatching { capture.poll() }.isFailure)
    check(runCatching { capture.start() }.isFailure)
    val bad = FakeInput().apply { result = -1 }
    val failed = ClassicPcmCaptureSession(ClassicPcmDjBridge(ClassicDjEngine(ClassicMusicCoordinator(transport))), bad, { 1L })
    failed.start()
    check(runCatching { failed.poll() }.isFailure)
    check(failed.state() == ClassicPcmCaptureSession.State.FAILED && bad.closes == 1)
    failed.close(); check(bad.closes == 1)
    println("PASS capture lifecycle, monotonic clock, partial frames, failure cleanup")
}
