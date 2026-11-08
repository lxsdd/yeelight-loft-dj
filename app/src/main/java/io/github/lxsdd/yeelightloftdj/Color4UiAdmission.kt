package io.github.lxsdd.yeelightloftdj

import java.util.ArrayDeque

/**
 * Pure offline admission model for EXPLICIT UI events only. Does not open sockets,
 * schedule retries, or dispatch on acknowledgement/timeout. Not wired to Android UI.
 * All callers must use the same instance for each fleet.
 */
class Color4UiAdmission(
    private val maxPerMinute: Int = 24,
    private val spacingMs: Long = 1_500L,
    private val windowMs: Long = 60_000L,
) {
    enum class Kind { RGB, BRIGHTNESS }
    data class Request(val kind: Kind, val value: Int, val ticket: Long)
    private class Lamp {
        val sent = ArrayDeque<Long>()
        var lastSent: Long? = null
        var inflightTicket: Long? = null
        var quarantined = false
        var pendingRgb: Int? = null
        var pendingBrightness: Int? = null
    }
    private val lamps = mutableMapOf<String, Lamp>()
    private var nextTicket = 1L

    init {
        require(maxPerMinute in 1..24 && spacingMs >= 1_500 && windowMs >= 60_000)
    }

    /** Unsupported devices fail closed; never accumulate a replay backlog. */
    @Synchronized
    fun offer(deviceId: String, kind: Kind, value: Int, supported: Boolean): Boolean {
        if (deviceId.isBlank()) return false
        if (!supported) {
            lamps[deviceId]?.let {
                it.pendingRgb = null
                it.pendingBrightness = null
                it.quarantined = true
            }
            return false
        }
        if (kind == Kind.RGB && value !in 0..0xFFFFFF) return false
        if (kind == Kind.BRIGHTNESS && value !in 1..100) return false
        val lamp = lamps.getOrPut(deviceId) { Lamp() }
        if (lamp.quarantined) return false
        when (kind) {
            Kind.RGB -> lamp.pendingRgb = value
            Kind.BRIGHTNESS -> lamp.pendingBrightness = value
        }
        return true
    }

    /**
     * Only a fresh, explicit UI interaction may call this method.
     * No timer, ACK, socket reconnect or background worker may invoke it.
     * The caller supplies the desired kind so RGB and brightness never overwrite
     * one another. The budget is consumed BEFORE any network attempt.
     */
    @Synchronized
    fun takeOnUiEvent(deviceId: String, kind: Kind, nowMs: Long, supported: Boolean): Request? {
        if (nowMs < 0) return null
        if (!supported) {
            lamps[deviceId]?.let {
                it.pendingRgb = null
                it.pendingBrightness = null
                it.quarantined = true
            }
            return null
        }
        val lamp = lamps[deviceId] ?: return null
        if (lamp.quarantined || lamp.inflightTicket != null) return null
        if (lamp.lastSent != null && nowMs < lamp.lastSent!!) {
            lamp.quarantined = true
            lamp.pendingRgb = null
            lamp.pendingBrightness = null
            return null
        }
        val value = when (kind) {
            Kind.RGB -> lamp.pendingRgb
            Kind.BRIGHTNESS -> lamp.pendingBrightness
        } ?: return null
        while (lamp.sent.isNotEmpty() && nowMs >= lamp.sent.first &&
            nowMs - lamp.sent.first >= windowMs) lamp.sent.removeFirst()
        if (lamp.sent.size >= maxPerMinute) return null
        val last = lamp.lastSent
        if (last != null && (nowMs < last || nowMs - last < spacingMs)) return null
        // Ticket exhaustion must fail closed BEFORE consuming a send-budget slot.
        // A wrapped generation could otherwise alias a stale completion.
        if (nextTicket == Long.MAX_VALUE) {
            lamp.quarantined = true
            lamp.pendingRgb = null
            lamp.pendingBrightness = null
            return null
        }
        val ticket = nextTicket++
        lamp.sent.addLast(nowMs)
        lamp.lastSent = nowMs
        lamp.inflightTicket = ticket
        when (kind) {
            Kind.RGB -> lamp.pendingRgb = null
            Kind.BRIGHTNESS -> lamp.pendingBrightness = null
        }
        return Request(kind, value, ticket)
    }

    /** Timeout/error consumes the budget. No implicit replay or dispatch. */
    @Synchronized
    fun complete(deviceId: String, ticket: Long, success: Boolean, uncertainOrQuota: Boolean): Boolean {
        val lamp = lamps[deviceId] ?: return false
        if (lamp.inflightTicket != ticket || ticket <= 0L) return false
        lamp.inflightTicket = null
        if (!success || uncertainOrQuota) {
            lamp.quarantined = true
            lamp.pendingRgb = null
            lamp.pendingBrightness = null
        }
        return true
    }

    /** Explicit operator action, never automatic cooldown recovery. */
    @Synchronized
    fun clearQuarantineByUser(deviceId: String) {
        lamps[deviceId]?.let {
            if (it.inflightTicket == null) {
                it.quarantined = false
                it.pendingRgb = null
                it.pendingBrightness = null
            }
        }
    }

    /** Discard UI intents from a disconnected generation without refunding budget. */
    @Synchronized
    fun discardPending(deviceId: String) {
        lamps[deviceId]?.let {
            it.pendingRgb = null
            it.pendingBrightness = null
        }
    }

    @Synchronized
    fun isQuarantined(deviceId: String): Boolean = lamps[deviceId]?.quarantined == true
}
