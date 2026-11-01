package io.github.lxsdd.yeelightloftdj

/**
 * Only in-memory Chroma UDP preview coordination: no sockets, discovery,
 * production transport, TCP fallback, threads or hardware access.
 * Frame limits are test policies, not established OEM color4 rate limits.
 */
class ChromaOfflineLampSpec(
    val key: String,
    val session: ChromaUdpOfflineSession,
    val firmwareQuotaQuarantined: Boolean = false,
)

data class ChromaOfflineLaneSnapshot(
    val framesAttempted: Int,
    val simulatedTokenHeld: Boolean,
    val safetyStopped: Boolean,
)

class ChromaUdpOfflineFleet(specs: List<ChromaOfflineLampSpec>) {
    init {
        require(specs.isNotEmpty() && specs.size <= MAX_LAMPS)
        require(specs.all { it.key.isNotBlank() && it.key.length <= 64 })
        require(specs.map { it.key }.distinct().size == specs.size)
        // A single session may not be attached to multiple logical lamp keys.
        require(specs.map { it.session }.distinct().size == specs.size)
    }

    private class Lane(spec: ChromaOfflineLampSpec) {
        val session = spec.session
        var stopped = spec.firmwareQuotaQuarantined
        var beganAt: Long? = null
        var observedAt: Long? = null
        var colorAt: Long? = null
        var attempted = 0
        init { if (stopped) session.close() }
    }
    private val lanes = specs.associate { it.key to Lane(it) }

    fun begin(key: String, nowMs: Long): ChromaPreviewFrame? {
        val lane = lanes[key] ?: return null
        if (!allow(lane, nowMs) || lane.beganAt != null) return null
        val frame = lane.session.begin(nowMs) ?: run {
            close(lane)
            return null
        }
        lane.beganAt = nowMs
        return frame
    }

    fun acceptReply(
        key: String, senderIp: String, senderPort: Int,
        payload: String, nowMs: Long,
    ): ChromaPreviewEvent? {
        val lane = lanes[key] ?: return null
        if (lane.beganAt == null || !allow(lane, nowMs))
            return ChromaPreviewEvent.IGNORED_UNEXPECTED_REPLY
        val event = lane.session.acceptReply(senderIp, senderPort, payload, nowMs)
        if (event == ChromaPreviewEvent.CLOSED_ON_DEVICE_ERROR ||
            lane.session.state == ChromaPreviewState.CLOSED) close(lane)
        return event
    }

    fun keepAlive(key: String, nowMs: Long): ChromaPreviewFrame? {
        val lane = lanes[key] ?: return null
        if (lane.beganAt == null || !allow(lane, nowMs)) return null
        val frame = lane.session.keepAlive(nowMs)
        if (lane.session.state == ChromaPreviewState.CLOSED) close(lane)
        return frame
    }

    /**
     * Drops intermediate events without buffering or retries. Attempts, not
     * ACKs, count against the per-lamp cap; a second lane has its own budget.
     */
    fun color(key: String, rgb: Int, bright: Int, nowMs: Long): ChromaPreviewFrame? {
        val lane = lanes[key] ?: return null
        if (lane.beganAt == null || !allow(lane, nowMs)) return null
        if (lane.attempted >= MAX_FRAMES_PER_LAMP) {
            close(lane)
            return null
        }
        val last = lane.colorAt
        if (last != null && nowMs - last < MIN_FRAME_GAP_MS) return null
        val frame = lane.session.previewColor(rgb, bright, nowMs)
        if (lane.session.state == ChromaPreviewState.CLOSED) close(lane)
        if (frame != null) {
            lane.attempted++
            lane.colorAt = nowMs
            // Stop at the exact 45th attempt, even without another event.
            if (lane.attempted >= MAX_FRAMES_PER_LAMP) close(lane)
        }
        return frame
    }

    /** A validated firmware-quota event is terminal; no timer unlock. */
    fun quarantine(key: String): Boolean = stop(key)

    fun stop(key: String): Boolean {
        val lane = lanes[key] ?: return false
        close(lane)
        return true
    }

    fun snapshot(key: String): ChromaOfflineLaneSnapshot? =
        lanes[key]?.let {
            ChromaOfflineLaneSnapshot(it.attempted, !it.stopped && it.session.tokenHeld, it.stopped)
        }

    private fun allow(lane: Lane, nowMs: Long): Boolean {
        if (lane.stopped) return false
        if (lane.beganAt != null && lane.session.state == ChromaPreviewState.CLOSED) {
            close(lane)
            return false
        }
        val prior = lane.observedAt
        val start = lane.beganAt
        if (nowMs < 0 || (prior != null && nowMs < prior) ||
            (start != null && nowMs - start >= MAX_LIFETIME_MS)) {
            close(lane)
            return false
        }
        lane.observedAt = nowMs
        return true
    }

    private fun close(lane: Lane) {
        lane.stopped = true
        lane.session.close()
    }

    companion object {
        const val MAX_LAMPS = 16
        const val MAX_FRAMES_PER_LAMP = 45
        const val MIN_FRAME_GAP_MS = 100L
        const val MAX_LIFETIME_MS = 15_000L
    }
}
