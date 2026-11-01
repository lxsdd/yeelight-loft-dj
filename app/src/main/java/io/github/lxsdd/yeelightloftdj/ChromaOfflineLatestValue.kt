package io.github.lxsdd.yeelightloftdj

/**
 * In-memory latest-value coalescer for an explicitly clocked CHROMA simulation.
 * Never creates a timer, socket, thread, device command or TCP fallback.
 *
 * One pending RGB value per lane is retained for at most [maxPendingAgeMs].
 * The caller must explicitly invoke poll() to preview the newest color.
 * No intermediate color history can be replayed after network loss.
 */
/** Group preflight results; no raw payload, IP, token or color leaves this API. */
enum class ChromaOfflinePollReadiness {
    READY, NO_CHANGE, RATE_DEFERRED, STALE_DROPPED, TERMINAL
}

class ChromaOfflineLatestValue(
    private val fleet: ChromaUdpOfflineFleet,
    private val maxPendingAgeMs: Long = 250L,
) {
    init { require(maxPendingAgeMs in 1L..1000L) }

    data class Snapshot(
        val pending: Boolean,
        val supersededSamples: Long,
        val expiredSamples: Long,
        val simulatedFramesIssued: Int,
        val terminal: Boolean,
    )

    private data class Color(val rgb: Int, val brightness: Int, val offeredAtMs: Long)
    private class Lane {
        var pending: Color? = null
        var lastClockMs: Long? = null
        var lastSent: Pair<Int, Int>? = null
        var superseded: Long = 0
        var expired: Long = 0
        var frames: Int = 0
    }

    // At most one entry per known simulated lamp; never keyed by device IP.
    private val lanes = mutableMapOf<String, Lane>()

    /**
     * Accept only active simulated sessions, valid colors and monotonic time.
     * Caller retains immediate UI state; only newest pending value is kept.
     */
    fun offer(key: String, rgb: Int, brightness: Int, nowMs: Long): Boolean {
        val session = fleet.snapshot(key) ?: return false
        if (!session.simulatedTokenHeld || session.safetyStopped || nowMs < 0 ||
            rgb !in 0..0xFFFFFF || brightness !in 1..100) {
            lanes[key]?.pending = null
            return false
        }
        // A token alone is insufficient: the OEM scene method must be
        // advertised, and the simulated session must still be within budget.
        if (!fleet.canStageColor(key, nowMs)) {
            lanes[key]?.pending = null
            return false
        }
        val lane = lanes.getOrPut(key) { Lane() }
        if (!advance(key, lane, nowMs)) return false
        if (lane.pending != null) lane.superseded++
        lane.pending = Color(rgb, brightness, nowMs)
        return true
    }

    /**
     * Zero background work. Call explicitly from a synthetic event loop only.
     * Rate rejection retains the latest sample (until expiry) with no queue.
     */
    /**
     * Deterministic admission for one pending sample. Called by the group
     * before *any* frame is emitted. RATE_DEFERRED keeps only the latest
     * value; STALE_DROPPED is terminal for an orchestrated group, while a
     * standalone coalescer may accept a new, fresh user sample later.
     *
     * This is an in-memory state check, not an actual network reservation.
     */
    fun preflight(key: String, nowMs: Long): ChromaOfflinePollReadiness {
        val lane = lanes[key] ?: return ChromaOfflinePollReadiness.TERMINAL
        if (!advance(key, lane, nowMs) || !fleet.canStageColor(key, nowMs)) {
            lane.pending = null
            return ChromaOfflinePollReadiness.TERMINAL
        }
        val desired = lane.pending ?: return ChromaOfflinePollReadiness.NO_CHANGE
        if (nowMs - desired.offeredAtMs > maxPendingAgeMs) {
            lane.pending = null
            lane.expired++
            return ChromaOfflinePollReadiness.STALE_DROPPED
        }
        if (desired.rgb to desired.brightness == lane.lastSent) {
            lane.pending = null
            return ChromaOfflinePollReadiness.NO_CHANGE
        }
        if (!fleet.canIssueColor(key, nowMs)) {
            if (fleet.snapshot(key)?.safetyStopped != false) {
                lane.pending = null
                return ChromaOfflinePollReadiness.TERMINAL
            }
            return ChromaOfflinePollReadiness.RATE_DEFERRED
        }
        return ChromaOfflinePollReadiness.READY
    }

    /** One simulator frame at most; no retry on an unexpected failed commit. */
    fun poll(key: String, nowMs: Long): ChromaPreviewFrame? {
        if (preflight(key, nowMs) != ChromaOfflinePollReadiness.READY) return null
        val lane = lanes[key] ?: return null
        val desired = lane.pending ?: return null
        val frame = fleet.color(key, desired.rgb, desired.brightness, nowMs)
        lane.pending = null
        if (frame == null) {
            // Preflight had approved the exact value at this clock. A failed
            // commit is ambiguous, never a reason to replay it.
            fleet.stop(key)
            return null
        }
        lane.frames++
        lane.lastSent = desired.rgb to desired.brightness
        return frame
    }

    /** Terminal quota or explicit stop clears pending state, never resends. */
    fun quarantine(key: String): Boolean {
        lanes[key]?.pending = null
        return fleet.quarantine(key)
    }

    fun stop(key: String): Boolean {
        lanes[key]?.pending = null
        return fleet.stop(key)
    }

    fun snapshot(key: String): Snapshot? {
        val status = fleet.snapshot(key) ?: return null
        val lane = lanes[key]
        return Snapshot(
            pending = !status.safetyStopped && lane?.pending != null,
            supersededSamples = lane?.superseded ?: 0L,
            expiredSamples = lane?.expired ?: 0L,
            simulatedFramesIssued = lane?.frames ?: 0,
            terminal = status.safetyStopped,
        )
    }

    private fun advance(key: String, lane: Lane, nowMs: Long): Boolean {
        val last = lane.lastClockMs
        if (nowMs < 0 || (last != null && nowMs < last)) {
            lane.pending = null
            fleet.stop(key)
            return false
        }
        lane.lastClockMs = nowMs
        return true
    }
}
