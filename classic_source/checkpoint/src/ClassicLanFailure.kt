package loftdj.classic

import java.io.IOException
import java.util.ArrayDeque

/** Fixed non-identifying failure types. Never serialize raw network/OEM messages. */
class ClassicLanFailure(val kind: Kind) : IOException(kind.name) {
    enum class Kind { LOCAL_BUDGET, DEVICE_QUOTA, DEVICE_REJECTED }
}
/**
 * Process-wide per-device normal-RPC budget with a two-command owned shutdown escrow.
 * A START admission preserves STOP and post-STOP get_prop capacity. No timer,
 * queue, retry, inferred firmware quota or extra device command is introduced.
 * Other applications and Home Assistant are outside this in-process budget.
 */
internal class ClassicLanRpcBudget(
    private val maxAttempts: Int = 8, private val windowMs: Long = 60_000L
) {
    enum class Phase { READ, START, STOP, VERIFY_STOP }
    private data class Lane(
        val events: ArrayDeque<Long> = ArrayDeque(),
        var owner: Any? = null,
        var remaining: Int = 0,
    )
    init { require(maxAttempts in 1..60 && windowMs in 1L..600_000L) }
    private val lanes = mutableMapOf<String, Lane>()

    /** Spend BEFORE network I/O, including an uncertain or unsuccessful attempt. */
    @Synchronized fun admit(device: String, nowMs: Long,
                            phase: Phase = Phase.READ, token: Any? = null): Boolean {
        if (device.isBlank()) return false
        val lane = lanes.getOrPut(device) { Lane() }
        val history = lane.events
        val previous = history.peekLast()
        // nanoTime's arbitrary signed origin is valid; a backwards clock is not.
        if (previous != null && nowMs < previous) return false
        while (history.isNotEmpty()) {
            val elapsed = nowMs - history.peekFirst()
            if (elapsed < 0L || elapsed >= windowMs) history.removeFirst() else break
        }
        val free = maxAttempts - history.size
        if (free <= 0) return false
        when (phase) {
            Phase.READ -> if (lane.owner != null) return false
            Phase.START -> {
                if (token == null || lane.owner != null || free < 3) return false
                lane.owner = token
                lane.remaining = 2
            }
            Phase.STOP -> {
                if (token == null || lane.owner !== token || lane.remaining != 2) return false
                lane.remaining = 1
            }
            Phase.VERIFY_STOP -> {
                if (token == null || lane.owner !== token || lane.remaining != 1) return false
                lane.remaining = 0
                lane.owner = null
            }
        }
        history.addLast(nowMs)
        return true
    }

    /** Local cleanup releases escrow ownership, never refunds a command. */
    @Synchronized fun retire(device: String, token: Any): Boolean {
        val lane = lanes[device] ?: return false
        if (lane.owner !== token) return false
        lane.owner = null
        lane.remaining = 0
        return true
    }
}
