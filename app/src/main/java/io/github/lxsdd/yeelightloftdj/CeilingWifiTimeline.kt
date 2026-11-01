package io.github.lxsdd.yeelightloftdj

/**
 * Pure function over manually exported/current AP-association events.
 * No FRITZ!Box access, polling, LAN commands, MAC/IP storage, or log upload.
 * Epoch milliseconds avoid local-clock/DST false periodicity.
 */
enum class CeilingApEventKind { DISCONNECTED, RECONNECTED }

data class CeilingApEvent(val epochMs: Long, val kind: CeilingApEventKind)

enum class CeilingWifiPattern {
    INSUFFICIENT_EVIDENCE,
    PERIODIC_NEAR_HOURLY,
    IRREGULAR_DISCONNECTS,
    INVALID_OR_MIXED_EVENTS,
}

data class CeilingWifiTimelineResult(
    val pattern: CeilingWifiPattern,
    val disconnectCount: Int,
    val intervalsMinutes: List<Long>,
) {
    /** Pattern is a clue for investigation, never a diagnosis of firmware. */
    val provesFirmwareBug: Boolean get() = false
}

enum class CeilingWifiCrossDevice { UNKNOWN, SIMULTANEOUS_AP_EVENTS, NOT_SIMULTANEOUS }

object CeilingWifiTimeline {
    private const val HOUR_MS = 3_600_000L
    private const val FIVE_MINUTES_MS = 300_000L

    fun analyze(events: List<CeilingApEvent>): CeilingWifiTimelineResult {
        val disconnected = events.filter { it.kind == CeilingApEventKind.DISCONNECTED }
        fun result(status: CeilingWifiPattern, minutes: List<Long> = emptyList()) =
            CeilingWifiTimelineResult(status, disconnected.size, minutes)
        if (events.size > 10_000 || events.any { it.epochMs < 0 } ||
            events.zipWithNext().any { (a, b) ->
                a.epochMs >= b.epochMs || a.kind == b.kind
            }) return result(CeilingWifiPattern.INVALID_OR_MIXED_EVENTS)

        if (disconnected.size < 4)
            return result(CeilingWifiPattern.INSUFFICIENT_EVIDENCE)
        val gaps = disconnected.zipWithNext().map { (a, b) -> b.epochMs - a.epochMs }
        val mins = gaps.map { it / 60_000L }
        // Do not interpolate missing events or label a regular 2-hour gap as
        // a 60-minute cycle; the AP must actually report each event.
        return if (gaps.all { kotlin.math.abs(it - HOUR_MS) <= FIVE_MINUTES_MS }) {
            result(CeilingWifiPattern.PERIODIC_NEAR_HOURLY, mins)
        } else {
            result(CeilingWifiPattern.IRREGULAR_DISCONNECTS, mins)
        }
    }

    /**
     * Optional comparison of two independent lists of *disconnect* events.
     * Three near-synchronous events are enough to suggest router/AP-wide
     * behavior, not to prove a router fault. No device identifiers are used.
     */
    fun compare(
        first: List<CeilingApEvent>,
        second: List<CeilingApEvent>,
        toleranceMs: Long = 60_000L,
    ): CeilingWifiCrossDevice {
        if (toleranceMs < 0 || toleranceMs > FIVE_MINUTES_MS ||
            analyze(first).pattern == CeilingWifiPattern.INVALID_OR_MIXED_EVENTS ||
            analyze(second).pattern == CeilingWifiPattern.INVALID_OR_MIXED_EVENTS) {
            return CeilingWifiCrossDevice.UNKNOWN
        }
        val a = first.filter { it.kind == CeilingApEventKind.DISCONNECTED }.map { it.epochMs }
        val b = second.filter { it.kind == CeilingApEventKind.DISCONNECTED }.map { it.epochMs }
        if (a.size < 3 || b.size < 3) return CeilingWifiCrossDevice.UNKNOWN
        var matches = 0
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val delta = a[i] - b[j]
            when {
                kotlin.math.abs(delta) <= toleranceMs -> { matches++; i++; j++ }
                delta < 0 -> i++
                else -> j++
            }
        }
        return if (matches >= 3) CeilingWifiCrossDevice.SIMULTANEOUS_AP_EVENTS
        else CeilingWifiCrossDevice.NOT_SIMULTANEOUS
    }
}
