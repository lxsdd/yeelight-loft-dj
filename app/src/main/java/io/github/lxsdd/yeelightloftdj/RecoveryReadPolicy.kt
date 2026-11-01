package io.github.lxsdd.yeelightloftdj

/** Pure, conservative decisions based on actual device reports.
 * A successful TCP connect is not proof that a color flow stopped.
 * No persistent journal is cleared unless every required fact is known.
 */
enum class RecoveryReadDecision {
    ALREADY_RESTORED,
    FLOW_STOPPED_NO_SNAPSHOT,
    SCANNER_OFF,
    FLOW_STILL_RUNNING,
    RESTORE_REQUIRED,
    UNKNOWN,
}

object RecoveryReadPolicy {
    fun decide(
        flowing: Boolean?,
        current: LampSnapshot?,
        original: LampSnapshot?,
        scannerOffRequired: Boolean,
        reportedPowerOn: Boolean? = current?.isOn,
    ): RecoveryReadDecision {
        if (scannerOffRequired) {
            return if (reportedPowerOn == false) RecoveryReadDecision.SCANNER_OFF
            else RecoveryReadDecision.UNKNOWN
        }
        if (flowing == true) return RecoveryReadDecision.FLOW_STILL_RUNNING
        if (flowing != false) return RecoveryReadDecision.UNKNOWN
        if (original == null) return RecoveryReadDecision.FLOW_STOPPED_NO_SNAPSHOT
        if (!original.isOn && reportedPowerOn == false)
            return RecoveryReadDecision.ALREADY_RESTORED
        if (current == null) return RecoveryReadDecision.UNKNOWN
        return if (matches(original, current)) RecoveryReadDecision.ALREADY_RESTORED
            else RecoveryReadDecision.RESTORE_REQUIRED
    }

    /** Only matching explicitly reported original values count as verified. */
    fun matches(original: LampSnapshot, current: LampSnapshot): Boolean {
        if (original.isOn != current.isOn) return false
        if (!original.isOn) return true
        if (original.brightness != current.brightness ||
            original.colorMode != current.colorMode) return false
        return when (original.colorMode) {
            1 -> original.rgb != null && original.rgb == current.rgb
            2 -> original.temperature != null && original.temperature == current.temperature
            3 -> original.hue != null && original.saturation != null &&
                original.hue == current.hue && original.saturation == current.saturation
            else -> false
        }
    }

    /** Only an explicit firmware music_on=0 establishes a stopped session.
     * Missing/unsupported values and music_on=1 never unlock restoration.
     */
    fun musicStillUnconfirmed(sessionTracked: Boolean, musicOn: Boolean?): Boolean =
        sessionTracked && musicOn != false

    /** A stopped music transport can be followed by static restoration
     * only if the real firmware reports no music and no running flow.
     */
    fun safeToRestoreAfterLostMusicChannel(
        musicOn: Boolean?, flowing: Boolean?,
    ): Boolean = musicOn == false && flowing == false

    fun canClear(
        d: RecoveryReadDecision,
        musicSessionUnconfirmed: Boolean = false,
    ): Boolean = !musicSessionUnconfirmed && d in setOf(
        RecoveryReadDecision.ALREADY_RESTORED,
        RecoveryReadDecision.FLOW_STOPPED_NO_SNAPSHOT,
        RecoveryReadDecision.SCANNER_OFF
    )
}
