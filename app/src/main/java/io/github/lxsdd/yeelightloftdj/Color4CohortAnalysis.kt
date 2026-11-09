package io.github.lxsdd.yeelightloftdj

/**
 * Pure, privacy-preserving cohort analysis for color4 outages.
 * No MAC/IP, no network, no discovery, no lamp writes. Never converts an
 * untested device or Yeelight/Google cloud response into successful LAN proof.
 */
enum class Color4LanOutcome {
    NOT_OBSERVED, // Includes "no reported issue" and cloud-only success.
    LAN_COMMAND_ACKNOWLEDGED,
    EXPLICIT_CLIENT_QUOTA_EXCEEDED,
    OTHER_LAN_ERROR,
}
enum class Color4RuntimeEvidence {
    WALL_POWER_OVER_50_DAYS_ONLY,
    VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS,
    UNKNOWN_OR_RECENT_REBOOT,
}
enum class Color4Exposure {
    UNKNOWN,
    LOW_OBSERVED_LOCAL_TRAFFIC,
    MULTIPLE_LOCAL_COMMANDS_OR_CLIENTS,
}
data class Color4CohortEntry(
    /** Anonymous ordinal; never use device ID, network address or alias. */
    val slot: Int,
    val firmware: String?,
    val runtime: Color4RuntimeEvidence,
    val lanOutcome: Color4LanOutcome,
    val exposure: Color4Exposure = Color4Exposure.UNKNOWN,
)
enum class Color4CohortConclusion {
    UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS,
    POWER_TIME_ALONE_NOT_DISCRIMINATING,
    WRAP_HYPOTHESIS_COMPATIBLE_BUT_NOT_PROVEN,
    CONTRADICTING_OR_MIXED_EVIDENCE,
}
data class Color4CohortResult(
    val conclusion: Color4CohortConclusion,
    val reportedQuotaCount: Int,
    val observedWorkingLanCount: Int,
    val untestedOrCloudOnlyCount: Int,
    val allEntriesHaveConfirmedFirmwareUptime: Boolean,
    /** We never make this claim based on observational cohort data alone. */
    val provenColor4FirmwareCause: Boolean = false,
    val safeNextStep: String,
)

object Color4CohortAnalysis {
    private const val MAX_LAMPS = 64
    private const val TARGET_FW = "2.1.7_0039"

    fun analyze(entries: List<Color4CohortEntry>): Color4CohortResult {
        require(entries.size <= MAX_LAMPS)
        require(entries.map { it.slot }.distinct().size == entries.size)
        require(entries.all { it.slot > 0 })
        val quota = entries.count {
            it.lanOutcome == Color4LanOutcome.EXPLICIT_CLIENT_QUOTA_EXCEEDED
        }
        val working = entries.count {
            it.lanOutcome == Color4LanOutcome.LAN_COMMAND_ACKNOWLEDGED
        }
        val notObserved = entries.count { it.lanOutcome == Color4LanOutcome.NOT_OBSERVED }
        val sameKnownFirmware = entries.isNotEmpty() &&
            entries.all { it.firmware?.trim() == TARGET_FW }
        val uniformWallPower = entries.isNotEmpty() && entries.all {
            it.runtime == Color4RuntimeEvidence.WALL_POWER_OVER_50_DAYS_ONLY
        }
        val allVerifiedFirmwareUptime = entries.isNotEmpty() && entries.all {
            it.runtime == Color4RuntimeEvidence.VERIFIED_FIRMWARE_UPTIME_OVER_49_DAYS_17_HOURS
        }
        val result = when {
            entries.isEmpty() || !sameKnownFirmware ->
                Color4CohortConclusion.UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS
            entries.any { it.lanOutcome == Color4LanOutcome.OTHER_LAN_ERROR } ->
                Color4CohortConclusion.CONTRADICTING_OR_MIXED_EVIDENCE
            uniformWallPower && quota > 0 && working > 0 ->
                Color4CohortConclusion.POWER_TIME_ALONE_NOT_DISCRIMINATING
            quota > 0 ->
                Color4CohortConclusion.WRAP_HYPOTHESIS_COMPATIBLE_BUT_NOT_PROVEN
            else -> Color4CohortConclusion.UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS
        }
        return Color4CohortResult(
            conclusion = result,
            reportedQuotaCount = quota,
            observedWorkingLanCount = working,
            untestedOrCloudOnlyCount = notObserved,
            allEntriesHaveConfirmedFirmwareUptime = allVerifiedFirmwareUptime,
            safeNextStep = when (result) {
                Color4CohortConclusion.POWER_TIME_ALONE_NOT_DISCRIMINATING ->
                    "Gleiche Netzstromdauer genügt nicht als Unterschied: " +
                        "Firmware-Uptime und bereits vorhandene lokale Befehls-/Client-Spuren " +
                        "vergleichen, keine zusätzliche Lampenlast."
                Color4CohortConclusion.WRAP_HYPOTHESIS_COMPATIBLE_BUT_NOT_PROVEN ->
                    "Quota ist belegt, Ursache offen: Cloud/Google-Erfolg " +
                        "nicht als LAN-Erholung werten; keine Test-Kommandoflut."
                Color4CohortConclusion.CONTRADICTING_OR_MIXED_EVIDENCE ->
                    "Andere LAN-Fehler separat analysieren; Quota und " +
                        "WLAN-/Cloud-Ausfall nicht zusammenfassen."
                Color4CohortConclusion.UNKNOWN_FIRMWARE_OR_TOO_FEW_OBSERVATIONS ->
                    "Nur tatsächlich beobachtete lokale LAN-Antworten vergleichen, " +
                        "sonst Beobachtungsstatus unbekannt belassen."
            },
        )
    }
}
