package loftdj.classic

import java.io.IOException
import java.util.ArrayDeque

/** Fixed non-identifying failure types. Never serialize raw network/OEM messages. */
class ClassicLanFailure(val kind: Kind) : IOException(kind.name) {
    enum class Kind { LOCAL_BUDGET, DEVICE_QUOTA, DEVICE_REJECTED }
}
/** Shared, conservative, per-process and per-device LAN RPC budget.
 * Failed/uncertain writes and reads count. Reconnect does not reset history.
 * Never queue/retry or infer firmware behavior from local budget pressure.
 */
internal class ClassicLanRpcBudget(
    private val maxAttempts: Int = 8, private val windowMs: Long = 60_000L
) {
    init { require(maxAttempts in 1..60 && windowMs in 1L..600_000L) }
    private val events = mutableMapOf<String, ArrayDeque<Long>>()
    @Synchronized fun admit(device: String, nowMs: Long): Boolean {
        if (device.isBlank() || nowMs < 0L) return false
        val history = events.getOrPut(device) { ArrayDeque<Long>() }
        val previous = history.peekLast()
        if (previous != null && nowMs < previous) return false
        while (history.isNotEmpty() && nowMs - history.peekFirst() >= windowMs)
            history.removeFirst()
        if (history.size >= maxAttempts) return false
        history.addLast(nowMs)
        return true
    }
}
