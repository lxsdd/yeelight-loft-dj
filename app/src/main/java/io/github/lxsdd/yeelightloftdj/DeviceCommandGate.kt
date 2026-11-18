package io.github.lxsdd.yeelightloftdj

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * One per-IP command owner for all classic Yeelight LAN control commands.
 *
 * A sliding count alone permits 45 messages in a single burst. That is NOT a
 * safe traffic shape for older Wi-Fi bulb firmware: space command groups out,
 * count every attempted message, and do not queue an entire minute of work.
 *
 * "client quota exceeded" is a FIRMWARE error. Unlike our local deferral, it
 * quarantines this bulb until an explicit, successful, read-only recovery probe.
 * Quarantined bulbs never receive automatic retries from music/shows/discovery.
 */
class DeviceCommandGate(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxCommandsPerMinute: Int = 24,
    private val windowMs: Long = 60_000L,
    private val minSpacingMs: Long = 1_500L,
    private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    private val onQuarantined: (String) -> Unit = {},
    private val onRecovered: (String) -> Unit = {},
) {
    init {
        require(maxCommandsPerMinute >= 1)
        require(windowMs > 0 && minSpacingMs >= 0)
    }
    private data class State(
        val timestamps: ArrayDeque<Long> = ArrayDeque(),
        var lastCommandAt: Long? = null,
        var quarantined: Boolean = false,
        var lastProbeAt: Long? = null,
    )
    private val states = ConcurrentHashMap<String, State>()
    private fun state(ip: String): State = states.computeIfAbsent(ip) { State() }

    class Deferred(message: String) : IllegalStateException(message)

    private fun trim(s: State, now: Long) {
        while (s.timestamps.isNotEmpty() && now - s.timestamps.first >= windowMs) {
            s.timestamps.removeFirst()
        }
    }

    private fun pacedAdmission(s: State, requests: Int) {
        val now = nowMs()
        trim(s, now)
        if (s.timestamps.size + requests > maxCommandsPerMinute) {
            val wait = (windowMs - (now - s.timestamps.first)).coerceAtLeast(0)
            throw Deferred("Lokales Yeelight-Sicherheitsbudget: kein Befehl gesendet. " +
                "In ${(wait + 999) / 1000} Sekunden erneut versuchen.")
        }
        // At most one short spacing wait inside a serialized per-bulb action.
        // Delay is bounded and does NOT schedule a delayed background retry.
        val elapsed = s.lastCommandAt?.let { now - it }
        if (elapsed != null && elapsed < minSpacingMs) {
            sleepMs(minSpacingMs - elapsed)
        }
        val sentAt = nowMs()
        trim(s, sentAt)
        if (s.timestamps.size + requests > maxCommandsPerMinute) {
            throw Deferred("Lokales Yeelight-Sicherheitsbudget: kein Befehl gesendet.")
        }
        repeat(requests) { s.timestamps.addLast(sentAt) }
        s.lastCommandAt = sentAt
    }

    private fun quarantine(ip: String, s: State) {
        if (!s.quarantined) {
            s.quarantined = true
            onQuarantined(ip)
        }
    }

    fun markQuarantined(ip: String) {
        require(ip.isNotBlank())
        val s = state(ip)
        synchronized(s) { s.quarantined = true }
    }

    fun isQuarantined(ip: String): Boolean {
        val s = states[ip] ?: return false
        synchronized(s) { return s.quarantined }
    }

    fun <T> run(ip: String, requests: Int = 1, action: () -> T): T {
        require(ip.isNotBlank() && requests in 1..maxCommandsPerMinute)
        val s = state(ip)
        synchronized(s) {
            if (s.quarantined) throw Deferred(
                "Yeelight-Firmware meldete client quota exceeded. " +
                    "Keine weiteren LAN-Kommandos – ausdrücklich LAN-Wiederherstellung prüfen."
            )
            pacedAdmission(s, requests)
            return try {
                action()
            } catch (e: Exception) {
                if (QuotaRecoveryPolicy.isQuotaRejection(e.message)) quarantine(ip, s)
                throw e
            }
        }
    }

    fun recordFirmwareRejection(ip: String, text: String?) {
        if (!QuotaRecoveryPolicy.isQuotaRejection(text)) return
        val s = state(ip)
        synchronized(s) { quarantine(ip, s) }
    }

    /**
     * ONE intentionally requested read-only attempt, at most once per minute.
     * A non-throwing (but invalid) response must NOT clear the quarantine;
     * callers must throw unless the bulb really returned a valid property.
     */
    fun <T> explicitRecoveryProbe(ip: String, action: () -> T): T {
        require(ip.isNotBlank())
        val s = state(ip)
        synchronized(s) {
            if (!s.quarantined) throw Deferred("Diese Lampe hat keine Firmware-LAN-Sperre.")
            val now = nowMs()
            val previous = s.lastProbeAt
            if (previous != null && now - previous < 60_000L) {
                throw Deferred("Recovery-Prüfung nur einmal pro Minute zulässig.")
            }
            s.lastProbeAt = now
            pacedAdmission(s, 1)
            val result = action() // never retry an ambiguous attempt
            // Commit removal of the persisted firmware block FIRST.
            // If storage fails, keep the in-memory gate quarantined as well:
            // no new control request may pass a half-recovered state.
            onRecovered(ip)
            s.quarantined = false
            s.lastProbeAt = null
            return result
        }
    }

    /** Remaining normal command capacity for optional beat effects. */
    fun remaining(ip: String): Int {
        val s = states[ip] ?: return maxCommandsPerMinute
        synchronized(s) {
            if (s.quarantined) return 0
            trim(s, nowMs())
            return (maxCommandsPerMinute - s.timestamps.size).coerceAtLeast(0)
        }
    }

    fun consumed(ip: String): Int {
        val s = states[ip] ?: return 0
        synchronized(s) {
            trim(s, nowMs())
            return s.timestamps.size
        }
    }
}
