package io.github.lxsdd.yeelightclassicdj.runtime

import android.media.AudioRecord
import android.media.projection.MediaProjection

/** Real compiled offline input: source reuse, revoke, repeated stop, failure. */
fun main() {
    AudioRecord.minimum = 1024
    AudioRecord.initialized = true
    val before = AudioRecord.releases
    val projection = MediaProjection()
    val input = ClassicPlaybackCaptureInput.open(projection, 16000)
    check(input.read(ShortArray(512)) == 0)
    input.close()
    input.close()
    check(AudioRecord.releases == before + 1)
    check(projection.stopped)
    val revokedProjection = MediaProjection()
    val revoked = ClassicPlaybackCaptureInput.open(revokedProjection, 16000)
    revokedProjection.stop()
    var failed = false
    try { revoked.read(ShortArray(256)) } catch (_: IllegalStateException) { failed = true }
    check(failed) { "Revoked projection must not allow stale PCM" }
    revoked.close()
    AudioRecord.minimum = -1
    failed = false
    try { ClassicPlaybackCaptureInput.open(MediaProjection(), 16000) }
    catch (_: IllegalStateException) { failed = true }
    check(failed) { "Unsupported PCM format must fail closed" }
    AudioRecord.minimum = 1024
    println("PASS compiled classic internal playback capture")
}
