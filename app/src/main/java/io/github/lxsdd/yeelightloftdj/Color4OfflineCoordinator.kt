package io.github.lxsdd.yeelightloftdj

/**
 * Pure offline coordinator of explicit UI admission and TCP receipt identity.
 * Never opens sockets, dispatches timers, retries or invokes Android APIs.
 * All lamps in one fleet must share a single coordinator instance.
 */
class Color4OfflineCoordinator(
    private val admission: Color4UiAdmission = Color4UiAdmission(),
    private val ledger: Color4TcpResponseLedger = Color4TcpResponseLedger(),
) {
    data class Dispatch(
        val deviceId: String,
        val kind: Color4UiAdmission.Kind,
        val value: Int,
        val ticket: Long,
        val session: Color4TcpResponseLedger.Session,
        val protocolId: Long,
    )
    private val sessions = mutableMapOf<String, Color4TcpResponseLedger.Session>()
    private val inFlight = mutableMapOf<String, Dispatch>()

    /** The caller has verified this model-only socket generation externally. */
    @Synchronized
    fun openSession(deviceId: String, supported: Boolean): Color4TcpResponseLedger.Session? {
        if (deviceId.isBlank() || !supported || isQuarantined(deviceId) || sessions.containsKey(deviceId)) return null
        // An intent offered before this socket generation must never cross into it.
        admission.discardPending(deviceId)
        val session = ledger.open(deviceId) ?: return null
        sessions[deviceId] = session
        return session
    }

    @Synchronized
    fun offer(deviceId: String, kind: Color4UiAdmission.Kind, value: Int, supported: Boolean): Boolean {
        // UI intents have no valid socket owner until a session is open.
        if (sessions[deviceId] == null && supported) return false
        val accepted = admission.offer(deviceId, kind, value, supported)
        if (!supported) sessions[deviceId]?.let { closeSession(deviceId, it) }
        return accepted
    }

    /** Must be called ONLY for a fresh UI action. Consumes budget before begin. */
    @Synchronized
    fun takeOnExplicitUiEvent(
        deviceId: String, kind: Color4UiAdmission.Kind, nowMs: Long,
        supported: Boolean, session: Color4TcpResponseLedger.Session, protocolId: Long,
    ): Dispatch? {
        if (sessions[deviceId] !== session) return null
        if (!supported) {
            admission.takeOnUiEvent(deviceId, kind, nowMs, false)
            closeSession(deviceId, session)
            return null
        }
        if (isQuarantined(deviceId) || inFlight.containsKey(deviceId)) return null
        val request = admission.takeOnUiEvent(deviceId, kind, nowMs, true) ?: return null
        if (!ledger.begin(deviceId, session, request.ticket, protocolId)) {
            // A consumed budget slot is never restored after a failed begin.
            admission.complete(deviceId, request.ticket, false, true)
            closeSession(deviceId, session)
            return null
        }
        return Dispatch(deviceId, request.kind, request.value, request.ticket, session, protocolId)
            .also { inFlight[deviceId] = it }
    }

    @Synchronized
    fun onReply(
        deviceId: String, session: Color4TcpResponseLedger.Session, protocolId: Long,
        success: Boolean, uncertainOrQuota: Boolean,
    ): Boolean {
        val active = inFlight[deviceId] ?: return false
        if (active.session !== session || active.protocolId != protocolId) return false
        val completion = ledger.reply(deviceId, session, protocolId, success, uncertainOrQuota) ?: return false
        inFlight.remove(deviceId)
        if (completion.ticket != active.ticket) {
            admission.complete(deviceId, active.ticket, false, true)
            closeSession(deviceId, session)
            return false
        }
        return admission.complete(deviceId, completion.ticket, completion.success, completion.uncertain)
    }

    @Synchronized
    fun onTimeout(deviceId: String, session: Color4TcpResponseLedger.Session, protocolId: Long): Boolean {
        val active = inFlight[deviceId] ?: return false
        if (active.session !== session || active.protocolId != protocolId) return false
        val completion = ledger.timeout(deviceId, session, protocolId) ?: return false
        inFlight.remove(deviceId)
        if (completion.ticket != active.ticket) {
            admission.complete(deviceId, active.ticket, false, true)
            closeSession(deviceId, session)
            return false
        }
        return admission.complete(deviceId, completion.ticket, false, true)
    }

    /** Disconnect leaves the budget spent and any pending write uncertain. */
    @Synchronized
    fun closeSession(deviceId: String, session: Color4TcpResponseLedger.Session): Boolean {
        if (sessions[deviceId] !== session) return false
        val completion = ledger.close(deviceId, session)
        // A clean disconnect must not replay queued UI values on a new socket.
        admission.discardPending(deviceId)
        sessions.remove(deviceId)
        val active = inFlight.remove(deviceId)
        if (active != null) {
            admission.complete(deviceId, active.ticket, false, true)
            if (completion?.ticket != active.ticket) return false
        }
        return true
    }

    /** Explicit operator recovery only, after both models are idle. */
    @Synchronized
    fun clearQuarantineByUser(deviceId: String): Boolean {
        if (deviceId.isBlank() || sessions.containsKey(deviceId) || inFlight.containsKey(deviceId)) return false
        if (!ledger.clearQuarantineByUser(deviceId) && ledger.isQuarantined(deviceId)) return false
        admission.clearQuarantineByUser(deviceId)
        return !isQuarantined(deviceId)
    }

    @Synchronized
    fun isQuarantined(deviceId: String): Boolean =
        admission.isQuarantined(deviceId) || ledger.isQuarantined(deviceId)
}
