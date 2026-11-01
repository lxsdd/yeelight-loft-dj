package io.github.lxsdd.yeelightloftdj

/**
 * An explicit, time-bounded, single-device Color4 acceptance test. No automatic
 * capability probing or fallback onto the quota-limited normal LAN transport.
 * This is intentionally distinct from unrestricted user-selected music shows.
 */
internal object Color4MusicAcceptancePolicy {
    const val DURATION_MS = 15_000L
    // Independent bound on reverse-channel frames even if timing logic is
    // accidentally changed. Beat pacing itself remains at >=350ms.
    const val MAX_RGB_WRITES = 45

    fun refusal(
        selected: List<YeelightDevice>,
        blockedIps: Set<String>,
        recoveryPending: Boolean,
    ): String? {
        val distinct = selected.distinctBy { it.ip }
        if (distinct.size != 1) return "Für den Color4-Test genau eine Lampe auswählen (Couchtisch)."
        if (recoveryPending) return "Zuerst eine offene Wiederherstellung abschließen – keine neuen Befehle."
        val device = distinct.single()
        if (device.ip in blockedIps) return "Firmware-LAN-Sperre: diese Lampe darf nicht getestet werden."
        if (!device.model.equals("color4", ignoreCase = true) &&
            !device.model.endsWith(".color4", ignoreCase = true)) {
            return "Color4-Modell wurde nicht eindeutig über die Gerätesuche bestätigt."
        }
        if (!YeelightLan.supportsMusicMode(device)) {
            return "Color4 muss set_music und set_rgb ausdrücklich in SUPPORT melden."
        }
        return null
    }
}
