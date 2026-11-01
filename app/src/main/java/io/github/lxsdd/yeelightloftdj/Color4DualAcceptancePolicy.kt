package io.github.lxsdd.yeelightloftdj

/**
 * Two-bulb transactional acceptance. Physically inactive until a separately
 * approved signed test release deliberately enables LIVE_TEST_APPROVED.
 * No app setting, UI gesture or IPC can modify this compile-time gate.
 */
internal object Color4DualAcceptancePolicy {
    const val LIVE_TEST_APPROVED = false
    const val DURATION_MS = 10_000L
    const val MAX_TOTAL_RGB_WRITES = 40
    const val MIN_FRAME_INTERVAL_MS = 500L

    fun refusal(
        selected: List<YeelightDevice>,
        blockedIps: Set<String>,
        recoveryPending: Boolean,
        signedTestApproved: Boolean = LIVE_TEST_APPROVED,
    ): String? {
        val reason = Color4PairReadinessPolicy.refusal(selected, blockedIps, recoveryPending)
        if (reason != null) return reason
        if (!signedTestApproved) return "Gemeinsamer Zwei-Lampen-Test gesperrt: " +
            "separate Hardware- und signierte Testfreigabe erforderlich."
        return null
    }

    fun permitsFrame(
        lastSentAtMs: Long, nowMs: Long, totalWriteAttempts: Long,
        chosenCount: Int, liveChannels: Int,
    ): Boolean =
        chosenCount == 2 && liveChannels == 2 && totalWriteAttempts >= 0 &&
            totalWriteAttempts + 2 <= MAX_TOTAL_RGB_WRITES &&
            nowMs >= lastSentAtMs &&
            nowMs - lastSentAtMs >= MIN_FRAME_INTERVAL_MS

    fun perDeviceReport(
        ips: List<String>,
        attempts: Map<String, Long>,
        failures: Map<String, Long>,
    ): String = ips.joinToString(" · ") { ip ->
        "Lampe ...${ip.substringAfterLast('.')} RGB ${attempts[ip] ?: 0L}, " +
            "Fehler ${failures[ip] ?: 0L}"
    }
}
