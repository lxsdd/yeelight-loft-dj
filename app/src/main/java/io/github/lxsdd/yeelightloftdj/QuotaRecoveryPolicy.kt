package io.github.lxsdd.yeelightloftdj

/**
 * One-minute local throttling after an explicit Yeelight LAN client-quota
 * rejection. The safety journal remains in private preferences, untouched.
 * This throttles retries, never changes the physical lamp or clears recovery.
 */
object QuotaRecoveryPolicy {
    const val COOLDOWN_MS = 60_000L

    fun isQuotaRejection(message: String?): Boolean =
        message?.contains("client quota exceeded", ignoreCase = true) == true

    fun remainingMs(now: Long, rejectedAt: Long?): Long {
        if (rejectedAt == null) return 0
        return (COOLDOWN_MS - (now - rejectedAt)).coerceIn(0L, COOLDOWN_MS)
    }

    fun canRetry(now: Long, rejectedAt: Long?): Boolean =
        remainingMs(now, rejectedAt) == 0L
}
