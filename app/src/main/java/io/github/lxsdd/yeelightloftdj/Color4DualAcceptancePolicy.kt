package io.github.lxsdd.yeelightloftdj

/**
 * Authorized opt-in RC7 two-bulb acceptance only, Couchtisch + Flur.
 * Does not authorize unlimited multi-lamp operation or untested bulbs.
 */
internal object Color4DualAcceptancePolicy {
    const val LIVE_TEST_APPROVED = true
    const val DURATION_MS = 10_000L
    const val MAX_TOTAL_RGB_WRITES = 40
    const val MIN_FRAME_INTERVAL_MS = 500L

    fun refusal(
        selected: List<YeelightDevice>,
        blockedIps: Set<String>,
        recoveryPending: Boolean,
        signedTestApproved: Boolean = LIVE_TEST_APPROVED,
        selectedNames: Set<String> = emptySet(),
    ): String? {
        val reason = Color4PairReadinessPolicy.refusal(selected, blockedIps, recoveryPending)
        if (reason != null) return reason
        if (!signedTestApproved) return "Zwei-Lampen-Test nur im freigegebenen RC7."
        // Human-readable names add a local authorization boundary. The
        // distinct-IP, SUPPORTED color4, quarantine and recovery checks above
        // remain mandatory. Do not authorize Wellness or untested Esstisch.
        if (selectedNames != setOf("Couchtisch", "Flur"))
            return "Dual-Test nur für Couchtisch + Flur freigegeben."
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
