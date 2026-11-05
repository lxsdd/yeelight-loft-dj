package io.github.lxsdd.yeelightloftdj

/**
 * Offline-only model of classic Yeelight TCP request/response correlation.
 * It does not open sockets, send packets, schedule timers or retry commands.
 * A caller must pass the generation of the socket which actually received a reply.
 * A socket close or timeout is uncertain: never silently replay the command.
 */
class Color4TcpResponseLedger {
    data class Session(val generation: Long)
    data class Completion(val ticket: Long, val success: Boolean, val uncertain: Boolean)
    private data class Inflight(val ticket: Long, val protocolId: Long)
    private class Device {
        var session: Session? = null
        var inflight: Inflight? = null
        var lastProtocolId = 0L
        var quarantined = false
    }
    private val devices = mutableMapOf<String, Device>()
    private var nextGeneration = 1L

    /** Opening a new socket never clears an earlier uncertain outcome. */
    @Synchronized
    fun open(deviceId: String): Session? {
        if (deviceId.isBlank() || nextGeneration == Long.MAX_VALUE) return null
        val d = devices.getOrPut(deviceId) { Device() }
        if (d.quarantined || d.session != null || d.inflight != null) return null
        return Session(nextGeneration++).also { d.session = it; d.lastProtocolId = 0L }
    }

    /** A successful admission ticket is required before recording a network attempt. */
    @Synchronized
    fun begin(deviceId: String, session: Session, ticket: Long, protocolId: Long): Boolean {
        val d = devices[deviceId] ?: return false
        if (ticket <= 0 || protocolId <= 0 || d.quarantined || d.session != session || d.inflight != null || protocolId <= d.lastProtocolId) return false
        d.lastProtocolId = protocolId
        d.inflight = Inflight(ticket, protocolId)
        return true
    }

    /** Ignore stale/duplicate/wrong-socket replies without mutating current state. */
    @Synchronized
    fun reply(deviceId: String, session: Session, protocolId: Long, success: Boolean, quotaOrUncertain: Boolean): Completion? {
        val d = devices[deviceId] ?: return null
        val active = d.inflight ?: return null
        if (d.session != session || active.protocolId != protocolId || protocolId <= 0) return null
        d.inflight = null
        if (!success || quotaOrUncertain) d.quarantined = true
        return Completion(active.ticket, success && !quotaOrUncertain, !success || quotaOrUncertain)
    }

    /** Timeout only affects the exact request in the exact session. */
    @Synchronized
    fun timeout(deviceId: String, session: Session, protocolId: Long): Completion? =
        reply(deviceId, session, protocolId, success = false, quotaOrUncertain = true)

    /** A lost socket makes any outstanding write uncertain, never retried. */
    @Synchronized
    fun close(deviceId: String, session: Session): Completion? {
        val d = devices[deviceId] ?: return null
        if (d.session != session) return null
        d.session = null
        val old = d.inflight
        d.inflight = null
        if (old != null) d.quarantined = true
        return old?.let { Completion(it.ticket, success = false, uncertain = true) }
    }

    /** Only an explicit user action after socket closure can recover. */
    @Synchronized
    fun clearQuarantineByUser(deviceId: String): Boolean {
        val d = devices[deviceId] ?: return false
        if (d.session != null || d.inflight != null) return false
        d.quarantined = false
        return true
    }

    @Synchronized
    fun isQuarantined(deviceId: String): Boolean = devices[deviceId]?.quarantined == true
}
