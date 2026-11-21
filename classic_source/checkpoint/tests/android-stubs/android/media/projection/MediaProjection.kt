package android.media.projection
import android.os.Handler

/** JVM-only consent holder: deterministic revocation; no real platform capture. */
class MediaProjection {
    open class Callback { open fun onStop() {} }
    private val callbacks = mutableSetOf<Callback>()
    var stopped = false
        private set
    fun registerCallback(callback:Callback, handler:Handler) { callbacks.add(callback) }
    fun unregisterCallback(callback:Callback) { callbacks.remove(callback) }
    fun stop() {
        if (!stopped) {
            stopped = true
            callbacks.toList().forEach { it.onStop() }
            callbacks.clear()
        }
    }
}
