package io.github.lxsdd.yeelightloftdj

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * One command owner per bulb, shared by every YeelightLan command/read path.
 * The budget is PER BULB across all of this app's TCP connections, not a
 * guarantee about third-party clients or the firmware's hidden counters.
 * Failed/unknown replies still consume budget: NEVER blind-retry a write.
 */
class DeviceCommandGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    // One persistent Yeelight TCP connection allows 60/min; reserve capacity
    // for delayed confirmations, firmware notifications and other clients.
    private val maxCommandsPerMinute: Int = 45,
    private val windowMs: Long = 60_000L,
    private val quotaCooldownMs: Long = 60_000L,
) {
    private data class State(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        var quotaBlockedUntil: Long = 0L
    )
    private val states = ConcurrentHashMap<String, State>()

    class Deferred(message: String) : IllegalStateException(message)

    fun <T> run(ip: String, requests: Int = 1, action: () -> T): T {
        require(ip.isNotBlank() && requests in 1..maxCommandsPerMinute)
        val state = states.computeIfAbsent(ip) { State() }
        synchronized(state) {
            val now = nowMs()
            if (now < state.quotaBlockedUntil) {
                throw Deferred("Yeelight-LAN-Limit: erneuter Versuch für diese Lampe " +
                    "in ${(state.quotaBlockedUntil - now + 999) / 1000} Sekunden")
            }
            while (state.timestamps.isNotEmpty() &&
                now - state.timestamps.first >= windowMs) {
                state.timestamps.removeFirst()
            }
            if (state.timestamps.size + requests > maxCommandsPerMinute) {
                val retry = windowMs - (now - state.timestamps.first)
                throw Deferred("Konservatives Yeelight-Befehlsbudget erreicht; " +
                    "erneuter Versuch für diese Lampe in ${(retry + 999) / 1000} Sekunden")
            }
            repeat(requests) { state.timestamps.addLast(now) }
            try {
                return action()
            } catch (e: Exception) {
                if (QuotaRecoveryPolicy.isQuotaRejection(e.message)) {
                    state.quotaBlockedUntil = nowMs() + quotaCooldownMs
                }
                throw e
            }
        }
    }

    /** Explicit Yeelight JSON error results can be returned as PowerProbe. */
    fun recordFirmwareRejection(ip: String, text: String?) {
        if (!QuotaRecoveryPolicy.isQuotaRejection(text)) return
        val state = states.computeIfAbsent(ip) { State() }
        synchronized(state) { state.quotaBlockedUntil = nowMs() + quotaCooldownMs }
    }

    /**
     * Read-only quota headroom for optional continuous effects. Unlike normal
     * commands, music beats must yield enough capacity for a user-requested
     * STOP and the original-state restoration. No reserve is a quota reset.
     */
    fun remaining(ip: String): Int {
        val state = states[ip] ?: return maxCommandsPerMinute
        synchronized(state) {
            val now = nowMs()
            if (now < state.quotaBlockedUntil) return 0
            while (state.timestamps.isNotEmpty() &&
                now - state.timestamps.first >= windowMs) state.timestamps.removeFirst()
            return (maxCommandsPerMinute - state.timestamps.size).coerceAtLeast(0)
        }
    }

    fun consumed(ip: String): Int {
        val state = states[ip] ?: return 0
        synchronized(state) {
            val now = nowMs()
            while (state.timestamps.isNotEmpty() &&
                now - state.timestamps.first >= windowMs) state.timestamps.removeFirst()
            return state.timestamps.size
        }
    }
}
