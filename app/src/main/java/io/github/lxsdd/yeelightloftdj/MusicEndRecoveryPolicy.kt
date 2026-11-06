package io.github.lxsdd.yeelightloftdj

/**
 * A narrow, read-only Music observation. A responsive "power" property is
 * network evidence, never proof that the lamp rebooted or Music is off.
 */
internal data class MusicEndStatus(val powerOn: Boolean?, val musicOn: Boolean?) {
    companion object {
        fun parse(values: List<String>): MusicEndStatus = MusicEndStatus(
            powerOn = when (values.getOrNull(0)) {
                "on" -> true
                "off" -> false
                else -> null
            },
            musicOn = when (values.getOrNull(1)) {
                "1" -> true
                "0" -> false
                else -> null
            }
        )
    }
}

internal enum class MusicEndRelease {
    FIRMWARE_CONFIRMED,
    OWN_SOCKET_CLOSED,
    OWNER_POWER_CYCLE,
    UNRESOLVED,
}

/**
 * Never mistake a successful LAN read, timeout, stop ACK or user claim for
 * firmware-reported music_on=0. An independently closed, app-owned reverse
 * socket is a separate stop path documented by the Yeelight protocol.
 *
 * A conscious physical power-cycle recovery can only free one old app-side
 * guard after a fresh, valid power read; it is not firmware verification.
 */
internal object MusicEndRecoveryPolicy {
    fun afterOwnStop(
        observed: MusicEndStatus?,
        reverseSocketWasOwnedAndClosed: Boolean,
        stopFailed: Boolean,
    ): MusicEndRelease {
        if (stopFailed || observed == null) return MusicEndRelease.UNRESOLVED
        if (observed.musicOn == true) return MusicEndRelease.UNRESOLVED
        if (observed.musicOn == false) return MusicEndRelease.FIRMWARE_CONFIRMED
        return if (reverseSocketWasOwnedAndClosed && observed.powerOn != null)
            MusicEndRelease.OWN_SOCKET_CLOSED
        else MusicEndRelease.UNRESOLVED
    }

    fun afterPhysicalPowerCycle(
        observed: MusicEndStatus?,
        userConfirmedMainsDisconnection: Boolean,
        safeLocalDevice: Boolean,
    ): MusicEndRelease {
        if (!safeLocalDevice || !userConfirmedMainsDisconnection || observed == null)
            return MusicEndRelease.UNRESOLVED
        if (observed.musicOn == true) return MusicEndRelease.UNRESOLVED
        if (observed.musicOn == false) return MusicEndRelease.FIRMWARE_CONFIRMED
        return if (observed.powerOn != null) MusicEndRelease.OWNER_POWER_CYCLE
        else MusicEndRelease.UNRESOLVED
    }

    fun admissionRefusal(observed: MusicEndStatus, unknownStatePermit: Boolean): String? =
        when {
            observed.powerOn != true ->
                "Music-Start erfordert eine nachweislich eingeschaltete Lampe."
            observed.musicOn == true ->
                "Firmware meldet music_on=1: fremde/alte Music-Sitzung nicht überschreiben."
            observed.musicOn == null && !unknownStatePermit ->
                "Firmware meldet music_on nicht zuverlässig. Zuerst eine alte " +
                    "Music-Sperre gezielt prüfen oder Stromtrennung bestätigen."
            else -> null
        }
}
